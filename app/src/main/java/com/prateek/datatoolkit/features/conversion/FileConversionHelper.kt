package com.prateek.datatoolkit.features.conversion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.prateek.datatoolkit.core.export.DocxReader
import com.prateek.datatoolkit.core.export.DocxWriter
import com.prateek.datatoolkit.features.pdf.PdfHelper
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * File Conversion: convert a picked file into a different format, covering three
 * families entirely on-device:
 *  - Documents: plain text, PDF, and Word (.docx) - any of the three to either
 *    of the other two, by round-tripping through plain text. This is a text-level
 *    conversion (fonts/layout aren't preserved), the same trade-off DocxWriter
 *    already makes elsewhere in the app.
 *  - Images: JPEG/PNG/WEBP/BMP, any to any, via Android's own Bitmap codec.
 *  - Audio: any audio format the device can decode (MP3, AAC/M4A, OGG, FLAC, WAV,
 *    ...) to WAV or M4A, via [AudioTranscoder].
 *  - Video: audio-track extraction to .m4a via [AudioTranscoder] - full video
 *    re-encoding needs a much heavier native codec stack than fits an on-device
 *    tool, so that's intentionally out of scope here.
 */
object FileConversionHelper {

    enum class FileCategory { DOCUMENT, IMAGE, AUDIO, VIDEO, UNKNOWN }

    data class ConversionFormat(val label: String, val extension: String, val mimeType: String)

    val TXT = ConversionFormat("TXT — Plain Text", "txt", "text/plain")
    val PDF = ConversionFormat("PDF", "pdf", "application/pdf")
    val DOCX = ConversionFormat("DOCX — Word", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")

    val JPEG = ConversionFormat("JPEG", "jpg", "image/jpeg")
    val PNG = ConversionFormat("PNG", "png", "image/png")
    val WEBP = ConversionFormat("WEBP", "webp", "image/webp")
    val BMP = ConversionFormat("BMP", "bmp", "image/bmp")

    val WAV = ConversionFormat("WAV — Uncompressed Audio", "wav", "audio/wav")
    val M4A = ConversionFormat("M4A — AAC Audio", "m4a", "audio/mp4")
    val EXTRACT_AUDIO = ConversionFormat("Extract Audio → M4A", "m4a", "audio/mp4")

    private val DOCUMENT_EXTENSIONS = setOf("txt", "text", "pdf", "docx")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "m4a", "aac", "ogg", "oga", "flac", "wma")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "mkv", "webm", "avi", "3gp", "3gpp", "m4v")

    fun detectCategory(extension: String, mimeType: String?): FileCategory {
        val ext = extension.lowercase()
        return when {
            ext in DOCUMENT_EXTENSIONS -> FileCategory.DOCUMENT
            ext in IMAGE_EXTENSIONS -> FileCategory.IMAGE
            ext in AUDIO_EXTENSIONS -> FileCategory.AUDIO
            ext in VIDEO_EXTENSIONS -> FileCategory.VIDEO
            mimeType == null -> FileCategory.UNKNOWN
            mimeType == "text/plain" || mimeType == "application/pdf" || mimeType.contains("wordprocessingml") -> FileCategory.DOCUMENT
            mimeType.startsWith("image/") -> FileCategory.IMAGE
            mimeType.startsWith("audio/") -> FileCategory.AUDIO
            mimeType.startsWith("video/") -> FileCategory.VIDEO
            else -> FileCategory.UNKNOWN
        }
    }

    /** Every sensible conversion target for [category], excluding the source's own format. */
    fun targetFormats(category: FileCategory, sourceExtension: String): List<ConversionFormat> {
        val ext = if (sourceExtension.lowercase() == "jpeg") "jpg" else sourceExtension.lowercase()
        return when (category) {
            FileCategory.DOCUMENT -> listOf(TXT, PDF, DOCX).filter { it.extension != ext }
            FileCategory.IMAGE -> listOf(JPEG, PNG, WEBP, BMP).filter { it.extension != ext }
            FileCategory.AUDIO -> listOf(WAV, M4A).filter { it.extension != ext }
            FileCategory.VIDEO -> listOf(EXTRACT_AUDIO)
            FileCategory.UNKNOWN -> emptyList()
        }
    }

    fun convert(input: File, sourceExtension: String, target: ConversionFormat, output: File) {
        when (target) {
            TXT, PDF, DOCX -> convertDocument(input, sourceExtension, target, output)
            JPEG, PNG, WEBP, BMP -> convertImage(input, target, output)
            WAV -> AudioTranscoder.toWav(input, output)
            M4A -> AudioTranscoder.toM4a(input, output)
            EXTRACT_AUDIO -> AudioTranscoder.extractAudioFromVideo(input, output)
            else -> throw IllegalArgumentException("Unsupported conversion target: ${target.label}")
        }
    }

    // ---- Documents ----------------------------------------------------

    private fun convertDocument(input: File, sourceExtension: String, target: ConversionFormat, output: File) {
        val text = when (sourceExtension.lowercase()) {
            "pdf" -> PdfHelper.extractText(input)
            "docx" -> DocxReader.extractText(input)
            else -> input.readText()
        }
        when (target.extension) {
            "txt" -> output.writeText(text)
            "pdf" -> PdfHelper.textToPdf(text, output)
            "docx" -> DocxWriter.writeText(text, output)
            else -> throw IllegalArgumentException("Unsupported document target: ${target.extension}")
        }
    }

    // ---- Images ---------------------------------------------------------

    private fun convertImage(input: File, target: ConversionFormat, output: File) {
        val bitmap = BitmapFactory.decodeFile(input.absolutePath)
            ?: throw IllegalArgumentException("Could not decode this image")
        try {
            FileOutputStream(output).use { out ->
                when (target.extension) {
                    "jpg" -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    "png" -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    "webp" -> {
                        @Suppress("DEPRECATION")
                        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                            Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                        bitmap.compress(format, 92, out)
                    }
                    "bmp" -> writeBmp(bitmap, out)
                    else -> throw IllegalArgumentException("Unsupported image target: ${target.extension}")
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Hand-rolled 24-bit uncompressed BMP encoder - Android's Bitmap.CompressFormat has no BMP option. */
    private fun writeBmp(bitmap: Bitmap, out: OutputStream) {
        val width = bitmap.width
        val height = bitmap.height
        val rowUnpadded = width * 3
        val rowPadding = (4 - rowUnpadded % 4) % 4
        val rowSize = rowUnpadded + rowPadding
        val pixelDataSize = rowSize * height

        val header = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN)
        header.put('B'.code.toByte()); header.put('M'.code.toByte())
        header.putInt(54 + pixelDataSize)  // file size
        header.putInt(0)                   // reserved
        header.putInt(54)                  // pixel data offset
        header.putInt(40)                  // DIB header size (BITMAPINFOHEADER)
        header.putInt(width)
        header.putInt(height)
        header.putShort(1)                 // color planes
        header.putShort(24)                // bits per pixel
        header.putInt(0)                   // no compression
        header.putInt(pixelDataSize)
        header.putInt(2835)                // ~72 DPI
        header.putInt(2835)
        header.putInt(0)                   // palette colors
        header.putInt(0)                   // important colors
        out.write(header.array())

        val pixels = IntArray(width)
        val row = ByteArray(rowSize)
        // BMP rows are stored bottom-to-top.
        for (y in height - 1 downTo 0) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            var idx = 0
            for (x in 0 until width) {
                val p = pixels[x]
                row[idx++] = (p and 0xFF).toByte()          // B
                row[idx++] = ((p shr 8) and 0xFF).toByte()  // G
                row[idx++] = ((p shr 16) and 0xFF).toByte() // R
            }
            for (p in 0 until rowPadding) row[idx++] = 0
            out.write(row)
        }
    }
}
