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
        val zip = try {
            ZipFile(file)
        } catch (e: java.io.IOException) {
            throw java.io.IOException("This isn't a valid .docx file (old .doc files aren't supported)")
        }
        zip.use {
            val entry = it.getEntry("word/document.xml")
                ?: throw java.io.IOException("This .docx has no readable document body")
            return it.getInputStream(entry).use { stream -> parseDocumentXml(stream) }
        }
    }

    private fun parseDocumentXml(input: InputStream): String {
        // Screen for a DOCTYPE declaration before this reaches any parser - see XmlSafety.
        val bytes = XmlSafety.readAndAssertNoDoctype(input)

        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")

        val sb = StringBuilder()
        var inText = false      // only <w:t> content is document text - not field codes or whitespace between tags
        var inTabStops = false  // <w:tabs><w:tab/> declares tab stops; it isn't a tab character
        var fallbackDepth = 0   // mc:Fallback duplicates the text of mc:Choice (text boxes) - skip it
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = localName(parser.name)
                    when {
                        name == "Fallback" -> fallbackDepth++
                        fallbackDepth > 0 -> {}
                        name == "t" -> inText = true
                        name == "tabs" -> inTabStops = true
                        name == "tab" && !inTabStops -> sb.append('\t')
                        name == "br" || name == "cr" -> sb.append('\n')
                    }
                }
                XmlPullParser.TEXT -> if (inText && fallbackDepth == 0) sb.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val name = localName(parser.name)
                    when {
                        name == "Fallback" -> if (fallbackDepth > 0) fallbackDepth--
                        name == "t" -> inText = false
                        name == "tabs" -> inTabStops = false
                        name == "p" && fallbackDepth == 0 -> sb.append('\n')
                    }
                }
            }
            event = parser.next()
        }
        return sb.toString()
    }

    /** Strips a namespace prefix (e.g. "w:t" -> "t") since we parse without namespace processing. */
    private fun localName(qualifiedName: String): String = qualifiedName.substringAfter(':')
}
