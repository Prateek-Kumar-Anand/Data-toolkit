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
 * exporting plain recognized/scraped text as paragraphs (and, via writeTextWithTables, tab-separated rows as tables).
 */
object DocxWriter {

    fun write(paragraphs: List<String>, output: File, tables: Boolean = false) {
        ZipOutputStream(output.outputStream()).use { zip ->
            writeEntry(zip, "[Content_Types].xml", CONTENT_TYPES)
            writeEntry(zip, "_rels/.rels", RELS)
            writeEntry(zip, "word/document.xml", buildDocumentXml(paragraphs, tables))
        }
    }

    /** Convenience overload: splits raw text on newlines into paragraphs. */
    fun writeText(text: String, output: File) =
        write(text.replace("\r\n", "\n").replace('\r', '\n').split("\n"), output)

    /** Like [writeText], but runs of tab-separated lines become real Word tables. */
    fun writeTextWithTables(text: String, output: File) =
        write(text.replace("\r\n", "\n").replace('\r', '\n').split("\n"), output, tables = true)

    /** Book text: `#`/`##`/`###` lines become bold headings, blank-line separated paragraphs. */
    fun writeBookText(text: String, output: File) {
        val body = StringBuilder()
        for (block in text.replace("\r\n", "\n").replace('\r', '\n').split("\n\n")) {
            val t = block.trim()
            if (t.isEmpty()) continue
            val level = t.takeWhile { it == '#' }.length.coerceAtMost(3)
            val content = stripInvalidXmlChars(if (level > 0) t.drop(level).trim() else t.replace('\n', ' '))
            body.append(
                if (level == 0) "<w:p><w:pPr><w:spacing w:after=\"160\"/></w:pPr><w:r><w:t xml:space=\"preserve\">${escapeXml(content)}</w:t></w:r></w:p>"
                else {
                    val size = when (level) { 1 -> 40; 2 -> 32; else -> 28 }
                    val jc = if (level == 1) "<w:jc w:val=\"center\"/>" else ""
                    "<w:p><w:pPr><w:keepNext/><w:spacing w:before=\"280\" w:after=\"140\"/>$jc</w:pPr>" +
                        "<w:r><w:rPr><w:b/><w:sz w:val=\"$size\"/></w:rPr><w:t xml:space=\"preserve\">${escapeXml(content)}</w:t></w:r></w:p>"
                }
            )
        }
        ZipOutputStream(output.outputStream()).use { zip ->
            writeEntry(zip, "[Content_Types].xml", CONTENT_TYPES)
            writeEntry(zip, "_rels/.rels", RELS)
            writeEntry(zip, "word/document.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:body>$body<w:sectPr/></w:body></w:document>")
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun paragraphXml(para: String): String {
        // A raw tab character inside <w:t> shows up as a blank in Word - real tabs need <w:tab/>.
        val runs = stripInvalidXmlChars(para).split('\t').joinToString("<w:tab/>") {
            "<w:t xml:space=\"preserve\">${escapeXml(it)}</w:t>"
        }
        return "<w:p><w:r>$runs</w:r></w:p>"
    }

    private fun tableXml(lines: List<String>): String {
        val rows = lines.map { stripInvalidXmlChars(it).split('\t') }
        val cols = rows.maxOf { it.size }
        val border = listOf("top", "left", "bottom", "right", "insideH", "insideV")
            .joinToString("") { "<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"808080\"/>" }
        val grid = "<w:tblGrid>" + "<w:gridCol w:w=\"${9000 / cols}\"/>".repeat(cols) + "</w:tblGrid>"
        val body = rows.joinToString("") { r ->
            "<w:tr>" + (0 until cols).joinToString("") { c ->
                val t = r.getOrElse(c) { "" }
                "<w:tc><w:tcPr><w:tcW w:w=\"${9000 / cols}\" w:type=\"dxa\"/></w:tcPr>" +
                    "<w:p><w:r><w:t xml:space=\"preserve\">${escapeXml(t)}</w:t></w:r></w:p></w:tc>"
            } + "</w:tr>"
        }
        return "<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>$border</w:tblBorders></w:tblPr>$grid$body</w:tbl><w:p/>"
    }

    private fun buildDocumentXml(paragraphs: List<String>, tables: Boolean = false): String {
        val body = StringBuilder()
        var i = 0
        while (i < paragraphs.size) {
            if (tables && paragraphs[i].contains('\t')) {
                var j = i
                while (j < paragraphs.size && paragraphs[j].contains('\t')) j++
                body.append(tableXml(paragraphs.subList(i, j)))
                i = j
            } else {
                body.append(paragraphXml(paragraphs[i]))
                i++
            }
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
