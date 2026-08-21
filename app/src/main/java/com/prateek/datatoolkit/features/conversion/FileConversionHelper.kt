package com.prateek.datatoolkit.features.conversion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.webkit.MimeTypeMap
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
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
    private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "m4a", "aac", "ogg", "oga", "flac", "wma", "opus", "amr")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "mkv", "webm", "avi", "3gp", "3gpp", "3g2", "m4v")

    /**
     * Common formats a "File Conversion" tool user would reasonably expect to be recognized -
     * spreadsheets, legacy office docs, archives - but that this tool doesn't convert (either
     * a different module owns them, e.g. Excel/CSV, or they're genuinely out of scope). Shown
     * with a plain-English label so the UI can say what the file actually is instead of the
     * misleading "unrecognized", which should be reserved for files we truly can't identify.
     */
    private val KNOWN_UNSUPPORTED_FORMATS = mapOf(
        "doc" to "Word 97–2003 (.doc)",
        "rtf" to "Rich Text (.rtf)",
        "odt" to "OpenDocument Text (.odt)",
        "pages" to "Apple Pages",
        "xls" to "Excel 97–2003 (.xls)",
        "xlsx" to "Excel spreadsheet — try the Excel/CSV tool",
        "xlsm" to "Excel spreadsheet — try the Excel/CSV tool",
        "csv" to "CSV spreadsheet — try the Excel/CSV tool",
        "tsv" to "TSV spreadsheet — try the Excel/CSV tool",
        "ods" to "OpenDocument Spreadsheet (.ods)",
        "numbers" to "Apple Numbers",
        "ppt" to "PowerPoint 97–2003 (.ppt)",
        "pptx" to "PowerPoint (.pptx)",
        "odp" to "OpenDocument Presentation (.odp)",
        "key" to "Apple Keynote",
        "epub" to "EPUB e-book",
        "zip" to "ZIP archive",
        "rar" to "RAR archive",
        "7z" to "7-Zip archive",
        "apk" to "Android app package (.apk)"
    )

    data class DetectionResult(
        val category: FileCategory,
        /**
         * The extension detection actually matched on. Usually the same as the filename's own
         * extension, but when that was missing or unusable, this may instead be an extension
         * recovered from the content provider's reported MIME type - callers should use THIS
         * (not the raw filename extension) for [targetFormats] and [convert], so a recovered
         * document/image/audio/video is treated consistently from here on.
         */
        val resolvedExtension: String,
        /** Set only when [category] is UNKNOWN but the file is a real, common format this
         *  tool just doesn't convert - lets the UI explain accurately instead of claiming the
         *  file itself couldn't be identified. */
        val recognizedButUnsupported: String? = null
    )

    private fun categoryFromExtension(ext: String): FileCategory? = when {
        ext.isEmpty() -> null
        ext in DOCUMENT_EXTENSIONS -> FileCategory.DOCUMENT
        ext in IMAGE_EXTENSIONS -> FileCategory.IMAGE
        ext in AUDIO_EXTENSIONS -> FileCategory.AUDIO
        ext in VIDEO_EXTENSIONS -> FileCategory.VIDEO
        else -> null
    }

    /** [mimeType] must already be lowercased/trimmed (or null). */
    private fun categoryFromMime(mimeType: String?): FileCategory? = when {
        mimeType == null -> null
        mimeType == "text/plain" || mimeType == "application/pdf" || mimeType.contains("wordprocessingml") -> FileCategory.DOCUMENT
        mimeType.startsWith("image/") -> FileCategory.IMAGE
        mimeType.startsWith("audio/") -> FileCategory.AUDIO
        mimeType.startsWith("video/") -> FileCategory.VIDEO
        else -> null
    }

    /**
     * Identifies a picked file from whatever the file picker handed back. Many content
     * providers (cloud storage, gallery/photo pickers, "shared from another app") report a
     * display name with no extension, a generic `application/octet-stream` MIME type, or
     * both individually incomplete - this checks four signals in order of reliability before
     * giving up:
     *  1. the filename's own extension
     *  2. the provider's reported MIME type
     *  3. an extension recovered *from* that MIME type via Android's own [MimeTypeMap] - the
     *     platform's canonical mime<->extension table, far more complete than any hand-rolled
     *     list, and exactly what recovers files whose name has no extension at all
     *  4. a known-but-out-of-scope check, so a real format we simply don't convert (.xlsx,
     *     .doc, a .zip, ...) is reported accurately instead of as "unrecognized"
     */
    fun detect(extension: String, mimeType: String?): DetectionResult {
        val ext = extension.trim().lowercase()
        // Strip any "; charset=..." parameter some providers append - MimeTypeMap and the
        // equality checks below both expect a bare "type/subtype".
        val mime = mimeType?.trim()?.lowercase()?.substringBefore(';')?.trim()
        val recoveredExt = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }?.lowercase()

        // 1. The filename's own extension is the most direct signal.
        categoryFromExtension(ext)?.let { return DetectionResult(it, ext) }

        // 2. An extension recovered from the mime type via Android's own mime<->extension
        //    table. Checked as an *extension* (not just a category) before the mime-prefix
        //    fallback below so that a pdf/docx picked up this way still resolves to a real
        //    "pdf"/"docx" extension - convertDocument() branches on that extension to decide
        //    how to read the file, so falling back to category-only would leave it with an
        //    empty extension and misread the file as plain text.
        if (recoveredExt != null) {
            categoryFromExtension(recoveredExt)?.let { return DetectionResult(it, recoveredExt) }
        }

        // 3. Mime-prefix fallback, for the rare mime MimeTypeMap's table doesn't know but is
        //    still clearly "image/…"/"audio/…"/etc. Attach whichever real extension we have.
        categoryFromMime(mime)?.let { category -> return DetectionResult(category, recoveredExt ?: ext) }

        // 4. Known-but-out-of-scope check, so a real format we just don't convert (.xlsx,
        //    .doc, a .zip, ...) is reported accurately instead of as "unrecognized".
        val friendly = KNOWN_UNSUPPORTED_FORMATS[ext] ?: recoveredExt?.let { KNOWN_UNSUPPORTED_FORMATS[it] }
        return DetectionResult(FileCategory.UNKNOWN, ext, recognizedButUnsupported = friendly)
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
