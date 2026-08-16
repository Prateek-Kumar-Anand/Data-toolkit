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
            "<w:p><w:r><w:t xml:space=\"preserve\">${escapeXml(stripInvalidXmlChars(para))}</w:t></w:r></w:p>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:body>$body<w:sectPr/></w:body></w:document>"
    }

    /**
     * Drops characters XML 1.0 doesn't allow anywhere in a document (the C0 control range other
     * than tab/LF/CR, and a couple of others - see the `Char` production in the XML spec).
     * Garbled/OCR-sourced or copy-pasted text can contain these; leaving one in would produce a
     * .docx that Word/LibreOffice/Google Docs reports as corrupt rather than one that just shows
     * an odd character. escapeXml alone doesn't help here - it only escapes the five characters
     * that are structurally significant to XML (& < > " '), not ones that are simply illegal.
     * Valid UTF-16 surrogate pairs (emoji, rare CJK, etc.) are left alone; only a lone/unpaired
     * surrogate - which isn't valid either way - is dropped along with everything else disallowed.
     */
    private fun stripInvalidXmlChars(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                sb.append(c).append(text[i + 1])
                i += 2
                continue
            }
            if (c == '\t' || c == '\n' || c == '\r' || c.code in 0x20..0xD7FF || c.code in 0xE000..0xFFFD) {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
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
