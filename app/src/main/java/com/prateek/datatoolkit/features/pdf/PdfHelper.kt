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
            PDDocument().use { newDoc ->
                for (i in startPage..endPage) {
                    if (i < 1 || i > doc.numberOfPages) continue
                    newDoc.addPage(doc.getPage(i - 1))
                }
                newDoc.save(output)
            }
        }
    }

    /** Builds a new PDF where each image becomes one full-page image. Handy after batch OCR scans. */
    fun imagesToPdf(images: List<Bitmap>, output: File) {
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
    fun textToPdf(text: String, output: File, fontSize: Float = 11f) {
        val font = PDType1Font.HELVETICA
        val margin = 50f
        val pageSize = PDRectangle.A4
        val maxWidth = pageSize.width - 2 * margin
        val leading = fontSize * 1.4f
        val linesPerPage = ((pageSize.height - 2 * margin) / leading).toInt().coerceAtLeast(1)

        val sanitized = sanitizeForWinAnsi(text)
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
    }

    private fun wrapLine(line: String, font: PDType1Font, fontSize: Float, maxWidth: Float): List<String> {
        val words = line.split(" ")
        val result = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            val width = font.getStringWidth(candidate) / 1000f * fontSize
            if (width > maxWidth && current.isNotEmpty()) {
                result.add(current.toString())
                current = StringBuilder(word)
            } else {
                current = StringBuilder(candidate)
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    /** PDType1Font.HELVETICA only supports WinAnsi - swap anything outside it for '?' rather than crash. */
    private fun sanitizeForWinAnsi(text: String): String =
        text.map { c -> if (c.code in 32..126 || c.code in 160..255 || c == '\n') c else '?' }.joinToString("")

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
