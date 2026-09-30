package com.prateek.datatoolkit.features.pdf

import android.graphics.Bitmap
import android.graphics.Matrix
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import java.io.File
import java.io.FileOutputStream

/**
 * PDF Operations: merge, split by page range, extract text (per page or
 * whole document), and build a PDF from one or more images (useful right
 * after a batch of OCR scans).
 */
object PdfHelper {

    fun extractText(file: File): String {
        PDDocument.load(file).use { doc ->
            return PDFTextStripper().getText(doc)
        }
    }

    fun extractTextPerPage(file: File): List<String> {
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper()
            val pages = mutableListOf<String>()
            for (i in 1..doc.numberOfPages) {
                stripper.startPage = i
                stripper.endPage = i
                pages.add(stripper.getText(doc))
            }
            return pages
        }
    }

    fun pageCount(file: File): Int {
        PDDocument.load(file).use { return it.numberOfPages }
    }

    /** Merges several PDFs (in the given order) into one output file. */
    fun merge(inputs: List<File>, output: File) {
        val merger = PDFMergerUtility()
        inputs.forEach { merger.addSource(it) }
        merger.destinationFileName = output.absolutePath
        merger.mergeDocuments(null)
    }

    /** Splits [startPage]..[endPage] (1-indexed, inclusive) out of [input] into [output]. */
    fun splitRange(input: File, startPage: Int, endPage: Int, output: File) {
        PDDocument.load(input).use { doc ->
            val total = doc.numberOfPages
            if (startPage < 1 || startPage > total) {
                throw IllegalArgumentException("This PDF has $total page(s) - the start page $startPage is outside it")
            }
            PDDocument().use { newDoc ->
                for (i in startPage..minOf(endPage, total)) {
                    newDoc.addPage(doc.getPage(i - 1))
                }
                newDoc.save(output)
            }
        }
    }

    /** Builds a new PDF where each image becomes one full-page image. Handy after batch OCR scans. */
    fun imagesToPdf(images: List<Bitmap>, output: File) {
        if (images.isEmpty()) throw IllegalArgumentException("None of the selected images could be read")
        PDDocument().use { doc ->
            for (bitmap in images) {
                val landscape = bitmap.width > bitmap.height
                val pageSize = if (landscape)
                    PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)
                else PDRectangle.A4

                val page = PDPage(pageSize)
                doc.addPage(page)

                val pdImage = LosslessFactory.createFromImage(doc, bitmap)
                val scale = minOf(pageSize.width / bitmap.width, pageSize.height / bitmap.height)
                val drawWidth = bitmap.width * scale
                val drawHeight = bitmap.height * scale
                val x = (pageSize.width - drawWidth) / 2
                val y = (pageSize.height - drawHeight) / 2

                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(pdImage, x, y, drawWidth, drawHeight)
                }
            }
            FileOutputStream(output).use { doc.save(it) }
        }
    }

    /**
     * Renders plain text (e.g. OCR output) into a simple, paginated PDF - word-wrapped
     * at [fontSize] with standard margins, breaking to a new page as needed. Used for
     * the OCR module's "export as PDF" option, where no original page layout exists.
     */
    fun textToPdf(text: String, output: File, fontSize: Float = 11f): Int {
        val font = PDType1Font.HELVETICA
        val margin = 50f
        val pageSize = PDRectangle.A4
        val maxWidth = pageSize.width - 2 * margin
        val leading = fontSize * 1.4f
        val linesPerPage = ((pageSize.height - 2 * margin) / leading).toInt().coerceAtLeast(1)

        // Normalize line endings and tabs first - '\r' and '\t' aren't WinAnsi-printable and
        // would otherwise each turn into a stray '?' at the end of every line / tab stop.
        var replaced = 0
        val sanitized = sanitizeForWinAnsi(
            text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")
        ) { replaced++ }
        val wrapped = mutableListOf<String>()
        sanitized.split("\n").forEach { paragraph ->
            if (paragraph.isBlank()) wrapped.add("") else wrapped.addAll(wrapLine(paragraph, font, fontSize, maxWidth))
        }
        if (wrapped.isEmpty()) wrapped.add("")

        PDDocument().use { doc ->
            var index = 0
            while (index < wrapped.size) {
                val page = PDPage(pageSize)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.setFont(font, fontSize)
                    cs.beginText()
                    cs.newLineAtOffset(margin, pageSize.height - margin)
                    var linesOnPage = 0
                    while (index < wrapped.size && linesOnPage < linesPerPage) {
                        if (linesOnPage > 0) cs.newLineAtOffset(0f, -leading)
                        cs.showText(wrapped[index].ifEmpty { " " })
                        index++
                        linesOnPage++
                    }
                    cs.endText()
                }
            }
            doc.save(output)
        }
        return replaced
    }

    private fun wrapLine(line: String, font: PDType1Font, fontSize: Float, maxWidth: Float): List<String> {
        val words = line.split(" ")
        val result = mutableListOf<String>()
        var current = StringBuilder()
        fun widthOf(t: String) = font.getStringWidth(t) / 1000f * fontSize
        for (rawWord in words) {
            // A single unbroken token (URL, long number) wider than the page must be split
            // or it runs off the edge of the page.
            val pieces = if (widthOf(rawWord) <= maxWidth) listOf(rawWord) else {
                val out = mutableListOf<String>()
                var chunk = StringBuilder()
                for (ch in rawWord) {
                    if (widthOf(chunk.toString() + ch) > maxWidth && chunk.isNotEmpty()) {
                        out.add(chunk.toString()); chunk = StringBuilder()
                    }
                    chunk.append(ch)
                }
                if (chunk.isNotEmpty()) out.add(chunk.toString())
                out
            }
            for (word in pieces) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            val width = font.getStringWidth(candidate) / 1000f * fontSize
            if (width > maxWidth && current.isNotEmpty()) {
                result.add(current.toString())
                current = StringBuilder(word)
            } else {
                current = StringBuilder(candidate)
            }
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    /** Typographic characters that WinAnsi (and so Helvetica) does encode, beyond Latin-1. */
    private val WIN_ANSI_EXTRAS = setOf(
        '\u2018', '\u2019', '\u201A', '\u201C', '\u201D', '\u201E', '\u2022', '\u2013', '\u2014',
        '\u2026', '\u20AC', '\u2122', '\u2020', '\u2021', '\u2030', '\u2039', '\u203A', '\u0152',
        '\u0153', '\u0160', '\u0161', '\u0178', '\u017D', '\u017E', '\u0192', '\u02C6', '\u02DC'
    )

    /**
     * PDType1Font.HELVETICA only supports WinAnsi. Curly quotes, dashes, bullets, ellipses and the
     * euro sign are part of WinAnsi and are kept; anything else (other scripts, emoji) becomes '?'
     * rather than crashing, and [onReplaced] is called once per replaced character so the caller
     * can tell the user.
     */
    private fun sanitizeForWinAnsi(text: String, onReplaced: () -> Unit): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when {
                c == '\n' -> sb.append(c)
                c == '\u00A0' -> sb.append(' ')
                c == '\u00AD' -> {} // soft hyphen: invisible, drop it
                c.code in 32..126 || c.code in 161..255 || c in WIN_ANSI_EXTRAS -> sb.append(c)
                c.isLowSurrogate() -> {} // second half of a pair already counted once
                c.code < 32 || c == '\u007F' -> {} // control characters: drop silently
                else -> { sb.append('?'); onReplaced() }
            }
        }
        return sb.toString()
    }

    /** Rotates every page by [degrees] (90/180/270), saving to [output]. */
    fun rotateAll(input: File, degrees: Int, output: File) {
        PDDocument.load(input).use { doc ->
            for (page in doc.pages) {
                page.rotation = (page.rotation + degrees) % 360
            }
            doc.save(output)
        }
    }
}
