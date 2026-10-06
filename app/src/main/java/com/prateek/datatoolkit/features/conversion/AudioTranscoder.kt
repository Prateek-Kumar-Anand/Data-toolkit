package com.prateek.datatoolkit.features.conversion

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio format conversion and video->audio extraction, built entirely on the platform's
 * android.media codec APIs (MediaExtractor/MediaCodec/MediaMuxer).
 *
 *  - [toWav]: decode to raw PCM, wrap in a WAV header.
 *  - [toM4a]: decode, then re-encode the PCM as AAC and mux into an .m4a container.
 *  - [extractAudioFromVideo]: remuxes the compressed audio track straight into .m4a when it is
 *    already AAC; any other audio codec is decoded and re-encoded to AAC instead.
 *
 * Decoded PCM is streamed through a temporary file rather than held in memory, so long
 * recordings (an hour of audio is ~600 MB of PCM) convert without running out of memory.
 */
object AudioTranscoder {

    private const val TIMEOUT_US = 10_000L
    private const val MAX_IDLE_LOOPS = 600 // ~12 s with no progress = a stalled/corrupt stream
    private const val IO_BUFFER = 256 * 1024

    private class PcmInfo(val sampleRate: Int, val channelCount: Int, val file: File, val byteCount: Long)

    fun toWav(input: File, output: File) {
        val pcm = decodeToPcmFile(input, output.parentFile)
        try {
            writeWav(pcm, output)
        } finally {
            pcm.file.delete()
        }
    }

    fun toM4a(input: File, output: File) {
        val pcm = decodeToPcmFile(input, output.parentFile)
        try {
            encodePcmToAac(pcm, output)
        } finally {
            pcm.file.delete()
        }
    }

    /** Copies (or, if needed, re-encodes) the audio track of a video file into a new .m4a. */
    fun extractAudioFromVideo(input: File, output: File) {
        val audioMime = openExtractor(input).let { ex ->
            try {
                findTrack(ex, "audio/")?.second?.getString(MediaFormat.KEY_MIME)
            } finally {
                ex.release()
            }
        } ?: throw IllegalArgumentException("This video has no audio track to extract")

        // MediaMuxer's MP4 container only accepts a few codecs; a video whose audio is MP3,
        // Opus, AC3, AMR... can't be remuxed, so it goes through the decode/encode path.
        if (audioMime != MediaFormat.MIMETYPE_AUDIO_AAC) {
            toM4a(input, output)
            return
        }

        val extractor = openExtractor(input)
        try {
            val (trackIndex, format) = findTrack(extractor, "audio/")
                ?: throw IllegalArgumentException("This video has no audio track to extract")
            extractor.selectTrack(trackIndex)

            val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var started = false
            try {
                val muxerTrack = muxer.addTrack(format)
                muxer.start()
                started = true

                val maxInputSize = intOr(format, MediaFormat.KEY_MAX_INPUT_SIZE, 1_048_576)
                val buffer = ByteBuffer.allocate(maxOf(maxInputSize, 1_048_576))
                val bufferInfo = MediaCodec.BufferInfo()
                var samples = 0

                while (true) {
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    bufferInfo.flags = extractor.sampleFlags.let { f ->
                        var out = 0
                        if (f and MediaExtractor.SAMPLE_FLAG_SYNC != 0) out = out or MediaCodec.BUFFER_FLAG_KEY_FRAME
                        if (f and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) out = out or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                        out
                    }
                    muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                    samples++
                    extractor.advance()
                }
                if (samples == 0) throw IOException("The audio track in this video is empty")
                muxer.stop()
                started = false
            } finally {
                if (started) runCatching { muxer.stop() }
                runCatching { muxer.release() }
            }
        } finally {
            extractor.release()
        }
    }

    // ---- helpers ------------------------------------------------------------

    private fun openExtractor(input: File): MediaExtractor {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            return extractor
        } catch (e: Exception) {
            extractor.release()
            throw IOException("This file's audio/video format isn't supported on this device")
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Pair<Int, MediaFormat>? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i to format
        }
        return null
    }

    private fun intOr(format: MediaFormat, key: String, default: Int): Int =
        if (format.containsKey(key)) format.getInteger(key) else default

    // ---- decode -------------------------------------------------------------

    private fun decodeToPcmFile(input: File, tempDir: File?): PcmInfo {
        val extractor = openExtractor(input)
        val pcmFile = File.createTempFile("pcm_", ".raw", tempDir)
        try {
            val (trackIndex, format) = findTrack(extractor, "audio/")
                ?: throw IllegalArgumentException("No audio track found in this file")
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("Unreadable audio track")
            var sampleRate = intOr(format, MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channelCount = intOr(format, MediaFormat.KEY_CHANNEL_COUNT, 2)
            var total = 0L

            FileOutputStream(pcmFile).buffered(IO_BUFFER).use { out ->
                // "pcm-encoding" 2 == 16-bit; absent means 16-bit too.
                val rawIs16Bit = mime == "audio/raw" && intOr(format, "pcm-encoding", 2) == 2
                if (rawIs16Bit) {
                    // Already PCM (e.g. WAV) - no decoder needed, just copy the samples out.
                    val buf = ByteBuffer.allocate(1 shl 20)
                    while (true) {
                        buf.clear()
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) break
                        out.write(buf.array(), 0, n)
                        total += n
                        extractor.advance()
                    }
                } else {
                    val codec = try {
                        MediaCodec.createDecoderByType(mime)
                    } catch (e: Exception) {
                        throw IOException("This device can't decode \"$mime\" audio")
                    }
                    try {
                        codec.configure(format, null, null, 0)
                        codec.start()

                        val info = MediaCodec.BufferInfo()
                        var scratch = ByteArray(64 * 1024)
                        var inputDone = false
                        var outputDone = false
                        var idle = 0

                        while (!outputDone) {
                            var progressed = false
                            if (!inputDone) {
                                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                                if (inIndex >= 0) {
                                    val inputBuffer = codec.getInputBuffer(inIndex)
                                        ?: throw IOException("Decoder input buffer unavailable")
                                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                    if (sampleSize < 0) {
                                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        inputDone = true
                                    } else {
                                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                        extractor.advance()
                                    }
                                    progressed = true
                                }
                            }

                            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                            if (outIndex >= 0) {
                                if (info.size > 0) {
                                    val outputBuffer = codec.getOutputBuffer(outIndex)
                                        ?: throw IOException("Decoder output buffer unavailable")
                                    outputBuffer.position(info.offset)
                                    outputBuffer.limit(info.offset + info.size)
                                    if (scratch.size < info.size) scratch = ByteArray(info.size)
                                    outputBuffer.get(scratch, 0, info.size)
                                    out.write(scratch, 0, info.size)
                                    total += info.size
                                }
                                codec.releaseOutputBuffer(outIndex, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                                progressed = true
                            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                val f = codec.outputFormat
                                sampleRate = intOr(f, MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                                channelCount = intOr(f, MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                                progressed = true
                            }

                            if (progressed) idle = 0
                            else if (++idle > MAX_IDLE_LOOPS) {
                                throw IOException("Decoding stalled - the file may be damaged")
                            }
                        }
                    } finally {
                        runCatching { codec.stop() }
                        runCatching { codec.release() }
                    }
                }
            }

            if (total <= 0L) throw IOException("No audio could be decoded from this file")
            return PcmInfo(sampleRate, channelCount, pcmFile, total)
        } catch (t: Throwable) {
            pcmFile.delete()
            throw t
        } finally {
            extractor.release()
        }
    }

    // ---- WAV ----------------------------------------------------------------

    private fun writeWav(pcm: PcmInfo, output: File) {
        if (pcm.byteCount > 0xFFFF_FFFFL - 36) throw IOException("Too long for a WAV file (4 GB limit)")
        val bitsPerSample = 16
        val byteRate = pcm.sampleRate * pcm.channelCount * bitsPerSample / 8
        val blockAlign = pcm.channelCount * bitsPerSample / 8

        FileOutputStream(output).buffered(IO_BUFFER).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt((36 + pcm.byteCount).toInt())
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1) // PCM
            header.putShort(pcm.channelCount.toShort())
            header.putInt(pcm.sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(pcm.byteCount.toInt())
            out.write(header.array())
            FileInputStream(pcm.file).use { it.copyTo(out, IO_BUFFER) }
        }
    }

    // ---- AAC / M4A ----------------------------------------------------------

    private fun readFully(input: InputStream, buf: ByteArray, length: Int): Int {
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    /** Folds 3+ channel PCM down to stereo (even channels -> left, odd -> right); AAC encoders
     *  on phones generally only accept mono/stereo. */
    private fun downmixToStereo(src: ByteArray, frames: Int, channels: Int, dst: ByteBuffer) {
        val bb = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN)
        dst.order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) {
            var l = 0; var r = 0; var ln = 0; var rn = 0
            for (c in 0 until channels) {
                val s = bb.getShort((f * channels + c) * 2).toInt()
                if (c % 2 == 0) { l += s; ln++ } else { r += s; rn++ }
            }
            dst.putShort((l / ln).toShort())
            dst.putShort((r / maxOf(rn, 1)).toShort())
        }
    }

    private fun encodePcmToAac(pcm: PcmInfo, output: File) {
        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val outChannels = if (pcm.channelCount > 2) 2 else pcm.channelCount
        val format = MediaFormat.createAudioFormat(mime, pcm.sampleRate, outChannels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (outChannels == 1) 96_000 else 128_000)
        }

        val encoder = try {
            MediaCodec.createEncoderByType(mime)
        } catch (e: Exception) {
            throw IOException("This device has no AAC encoder, so M4A output isn't available")
        }
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var muxerTrack = -1
            val info = MediaCodec.BufferInfo()
            val srcFrameBytes = 2 * pcm.channelCount
            val outFrameBytes = 2 * outChannels
            var scratch = ByteArray(0)
            var framesQueued = 0L
            var inputDone = false
            var outputDone = false
            var idle = 0

            FileInputStream(pcm.file).buffered(IO_BUFFER).use { src ->
                while (!outputDone) {
                    var progressed = false
                    if (!inputDone) {
                        val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val inputBuffer = encoder.getInputBuffer(inIndex)
                                ?: throw IOException("Encoder input buffer unavailable")
                            inputBuffer.clear()
                            val maxFrames = maxOf(1, inputBuffer.capacity() / outFrameBytes)
                            val want = maxFrames * srcFrameBytes
                            if (scratch.size < want) scratch = ByteArray(want)
                            val frames = readFully(src, scratch, want) / srcFrameBytes
                            if (frames <= 0) {
                                encoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                if (outChannels == pcm.channelCount) inputBuffer.put(scratch, 0, frames * srcFrameBytes)
                                else downmixToStereo(scratch, frames, pcm.channelCount, inputBuffer)
                                val ptsUs = framesQueued * 1_000_000L / pcm.sampleRate
                                encoder.queueInputBuffer(inIndex, 0, frames * outFrameBytes, ptsUs, 0)
                                framesQueued += frames
                            }
                            progressed = true
                        }
                    }

                    val outIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                muxerTrack = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                            progressed = true
                        }
                        outIndex >= 0 -> {
                            // Codec-config data is already carried by the track format from addTrack();
                            // writing it again as a sample would corrupt the file.
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && muxerStarted) {
                                val outputBuffer = encoder.getOutputBuffer(outIndex)
                                    ?: throw IOException("Encoder output buffer unavailable")
                                outputBuffer.position(info.offset)
                                outputBuffer.limit(info.offset + info.size)
                                muxer.writeSampleData(muxerTrack, outputBuffer, info)
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            progressed = true
                        }
                    }

                    if (progressed) idle = 0
                    else if (++idle > MAX_IDLE_LOOPS) throw IOException("Encoding stalled")
                }
            }

            if (!muxerStarted) throw IOException("Encoding produced no audio")
            muxer.stop()
            muxerStarted = false
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }
}
