package com.prateek.datatoolkit.core.export

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal, dependency-free .docx writer.
 *
 * A .docx is just a zip (OPC package) with a handful of small XML parts. Pulling in
 * Apache POI for this would add tens of MB to the app and isn't Android-friendly, so
 * instead we hand-write the three parts every Word/Google Docs/LibreOffice reader
 * needs: [Content_Types].xml, _rels/.rels, and word/document.xml. Good enough for
 * exporting plain recognized/scraped text as paragraphs - no styling, tables, or images.
 */
object DocxWriter {

    fun write(paragraphs: List<String>, output: File) {
        ZipOutputStream(output.outputStream()).use { zip ->
            writeEntry(zip, "[Content_Types].xml", CONTENT_TYPES)
            writeEntry(zip, "_rels/.rels", RELS)
            writeEntry(zip, "word/document.xml", buildDocumentXml(paragraphs))
        }
    }

    /** Convenience overload: splits raw text on newlines into paragraphs. */
    fun writeText(text: String, output: File) = write(text.split("\n"), output)

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun buildDocumentXml(paragraphs: List<String>): String {
        val body = paragraphs.joinToString("") { para ->
            "<w:p><w:r><w:t xml:space=\"preserve\">${escapeXml(para)}</w:t></w:r></w:p>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:body>$body<w:sectPr/></w:body></w:document>"
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private const val CONTENT_TYPES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "</Types>"

    private const val RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
        "</Relationships>"
}
