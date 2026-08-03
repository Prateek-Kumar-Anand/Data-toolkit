package com.prateek.datatoolkit.features.conversion

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio format conversion and video->audio extraction, built entirely on the
 * platform's android.media codec APIs (MediaExtractor/MediaCodec/MediaMuxer) -
 * no extra libraries, so it works for whatever audio format the device's own
 * codecs can decode (typically MP3, AAC/M4A, OGG, FLAC, WAV).
 *
 * Two different strategies are used depending on the target:
 *  - [toWav]: decode-only. Any compressed audio track is decoded to raw PCM
 *    and wrapped in a WAV header. No re-encoding, so this is fast and reliable.
 *  - [toM4a]: decode, then re-encode the PCM as AAC and mux into an .m4a
 *    container - needed because WAV has no compressed form to just copy from.
 *  - [extractAudioFromVideo]: no decode/encode at all - the audio track's
 *    compressed samples are copied (remuxed) straight into a new .m4a
 *    container, which is why it only works when the source's audio track is
 *    already AAC (true for the overwhelming majority of MP4/MOV files).
 */
object AudioTranscoder {

    private const val TIMEOUT_US = 10_000L

    private class PcmData(val sampleRate: Int, val channelCount: Int, val bytes: ByteArray)

    fun toWav(input: File, output: File) {
        val pcm = decodeToPcm(input)
        writeWav(pcm, output)
    }

    fun toM4a(input: File, output: File) {
        val pcm = decodeToPcm(input)
        encodePcmToAac(pcm, output)
    }

    /** Copies the audio track of a video file into a new .m4a container without re-encoding. */
    fun extractAudioFromVideo(input: File, output: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            val (trackIndex, format) = findTrack(extractor, "audio/")
                ?: throw IllegalArgumentException("This video has no audio track to extract")
            extractor.selectTrack(trackIndex)

            val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val muxerTrack = muxer.addTrack(format)
                muxer.start()

                val maxInputSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 1_048_576
                val buffer = ByteBuffer.allocate(maxOf(maxInputSize, 1_048_576))
                val bufferInfo = MediaCodec.BufferInfo()

                while (true) {
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    bufferInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                    extractor.advance()
                }
                muxer.stop()
            } finally {
                muxer.release()
            }
        } finally {
            extractor.release()
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

    private fun decodeToPcm(input: File): PcmData {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            val (trackIndex, format) = findTrack(extractor, "audio/")
                ?: throw IllegalArgumentException("No audio track found in this file")
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()

                val pcmOut = ByteArrayOutputStream()
                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false

                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inIndex)!!
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outIndex)!!
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)
                            pcmOut.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    // Negative outIndex (INFO_TRY_AGAIN_LATER / INFO_OUTPUT_FORMAT_CHANGED) just
                    // means "nothing to do this pass" - sampleRate/channelCount already came
                    // from the extractor's format, so a decoder format change needs no handling.
                }
                return PcmData(sampleRate, channelCount, pcmOut.toByteArray())
            } finally {
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun writeWav(pcm: PcmData, output: File) {
        val bitsPerSample = 16
        val byteRate = pcm.sampleRate * pcm.channelCount * bitsPerSample / 8
        val blockAlign = pcm.channelCount * bitsPerSample / 8

        FileOutputStream(output).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + pcm.bytes.size)
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
            header.putInt(pcm.bytes.size)
            out.write(header.array())
            out.write(pcm.bytes)
        }
    }

    private fun encodePcmToAac(pcm: PcmData, output: File) {
        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val format = MediaFormat.createAudioFormat(mime, pcm.sampleRate, pcm.channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
        }

        val encoder = MediaCodec.createEncoderByType(mime)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            var muxerTrack = -1
            var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()
            var pcmOffset = 0
            var inputDone = false
            var outputDone = false
            var presentationTimeUs = 0L
            val bytesPerFrame = 2 * pcm.channelCount // 16-bit samples

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inIndex)!!
                        val remaining = pcm.bytes.size - pcmOffset
                        if (remaining <= 0) {
                            encoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val chunkSize = minOf(inputBuffer.capacity(), remaining)
                            inputBuffer.clear()
                            inputBuffer.put(pcm.bytes, pcmOffset, chunkSize)
                            encoder.queueInputBuffer(inIndex, 0, chunkSize, presentationTimeUs, 0)
                            pcmOffset += chunkSize
                            presentationTimeUs += (chunkSize / bytesPerFrame) * 1_000_000L / pcm.sampleRate
                        }
                    }
                }

                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxerTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        // The codec-config buffer's data is already captured by addTrack() above
                        // via outputFormat - writing it again as a sample would corrupt the file.
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            val outputBuffer = encoder.getOutputBuffer(outIndex)!!
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(muxerTrack, outputBuffer, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    // else: INFO_TRY_AGAIN_LATER - nothing ready yet, loop again.
                }
            }
            muxer.stop()
        } finally {
            encoder.stop()
            encoder.release()
            muxer.release()
        }
    }
}
