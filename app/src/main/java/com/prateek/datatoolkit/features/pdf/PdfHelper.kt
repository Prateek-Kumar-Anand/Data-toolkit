package com.prateek.datatoolkit.features.pdf

import android.graphics.Bitmap
import android.graphics.Matrix
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
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
