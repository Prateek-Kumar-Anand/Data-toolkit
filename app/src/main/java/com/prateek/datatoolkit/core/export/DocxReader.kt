package com.prateek.datatoolkit.core.export

import android.util.Xml
import com.prateek.datatoolkit.core.xml.XmlSafety
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Minimal, dependency-free .docx text reader - the counterpart to [DocxWriter].
 *
 * A .docx is a zip (OPC package); the visible text lives in word/document.xml as a
 * sequence of <w:p> paragraphs containing <w:r> runs containing <w:t> text nodes.
 * Pulling in Apache POI just to read this back would cost tens of MB (see the note
 * on DocxWriter), so instead this walks the XML with the platform's built-in pull
 * parser and reads out <w:t> text, turning each </w:p> into a newline and each
 * <w:tab/>/<w:br/> into a tab/newline. Good enough to round-trip plain text - no
 * tables, headers/footers, or embedded objects.
 */
object DocxReader {

    fun extractText(file: File): String {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return ""
            return zip.getInputStream(entry).use { parseDocumentXml(it) }
        }
    }

    private fun parseDocumentXml(input: InputStream): String {
        // Screen for a DOCTYPE declaration before this reaches any parser - see XmlSafety.
        val bytes = XmlSafety.readAndAssertNoDoctype(input)

        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")

        val sb = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (localName(parser.name)) {
                    "tab" -> sb.append('\t')
                    "br", "cr" -> sb.append('\n')
                }
                XmlPullParser.TEXT -> sb.append(parser.text)
                XmlPullParser.END_TAG -> if (localName(parser.name) == "p") sb.append('\n')
            }
            event = parser.next()
        }
        return sb.toString()
    }

    /** Strips a namespace prefix (e.g. "w:t" -> "t") since we parse without namespace processing. */
    private fun localName(qualifiedName: String): String = qualifiedName.substringAfter(':')
}
