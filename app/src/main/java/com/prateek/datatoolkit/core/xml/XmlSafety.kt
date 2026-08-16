package com.prateek.datatoolkit.core.xml

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Guards against XXE / entity-expansion payloads in XML that this app parses out of
 * user-supplied .docx/.xlsx files (both are just zip archives of XML parts).
 *
 * None of the readers in [com.prateek.datatoolkit.core.export] or
 * [com.prateek.datatoolkit.features.excel] configure their own XML parser instance (the
 * primary .xlsx path hands the whole file to a third-party StAX-based reader, and the
 * fallback paths use the platform pull parser) - so rather than depend on a parser's
 * internal defaults for something this security-sensitive, every zip-embedded XML part
 * this app reads is screened for a `<!DOCTYPE` declaration first. No legitimate Office
 * Open XML part (document.xml, sheetN.xml, sharedStrings.xml, workbook.xml, *.rels) ever
 * contains one, so rejecting it outright has no false-positive cost and closes the XXE/
 * "billion laughs" class of attack regardless of what the actual parser would have done.
 */
object XmlSafety {

    /** Thrown instead of letting a DOCTYPE-bearing part reach any XML parser. */
    class UnsafeXmlException(message: String) : IOException(message)

    private val NEEDLE_UTF8 = "<!DOCTYPE".toByteArray(Charsets.US_ASCII)
    private val NEEDLE_UTF16LE = "<!DOCTYPE".toByteArray(Charsets.UTF_16LE)
    private val NEEDLE_UTF16BE = "<!DOCTYPE".toByteArray(Charsets.UTF_16BE)

    /** Reads [input] fully and throws [UnsafeXmlException] if it contains a DOCTYPE
     *  declaration; otherwise returns the bytes read so the caller can parse them without
     *  re-reading the stream. */
    fun readAndAssertNoDoctype(input: InputStream): ByteArray {
        val bytes = input.readBytes()
        assertNoDoctype(bytes)
        return bytes
    }

    fun assertNoDoctype(bytes: ByteArray) {
        if (containsCaseInsensitive(bytes, NEEDLE_UTF8) ||
            containsCaseInsensitive(bytes, NEEDLE_UTF16LE) ||
            containsCaseInsensitive(bytes, NEEDLE_UTF16BE)
        ) {
            throw UnsafeXmlException(
                "XML content declares a DOCTYPE, which isn't allowed here (blocks XXE / entity-expansion payloads)"
            )
        }
    }

    /**
     * Defense-in-depth pre-check for a whole zip-based document (.xlsx/.docx) before handing
     * it to a parser this app doesn't directly configure (e.g. the third-party fastexcel
     * reader) - decompresses every XML/rels part and screens each one. Used in addition to,
     * not instead of, the per-entry checks the individual readers already do on the parts
     * they read themselves.
     */
    fun assertZipHasNoDoctype(file: File) {
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name.lowercase()
                if (!(name.endsWith(".xml") || name.endsWith(".rels"))) continue
                zip.getInputStream(entry).use { assertNoDoctype(it.readBytes()) }
            }
        }
    }

    private fun containsCaseInsensitive(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        val last = haystack.size - needle.size
        outer@ for (start in 0..last) {
            for (i in needle.indices) {
                val h = haystack[start + i]
                val n = needle[i]
                // Only the ASCII letters in "<!DOCTYPE" need case-folding; '<', '!' compare exactly.
                val hFold = if (h in 'a'.code.toByte()..'z'.code.toByte()) (h - 32).toByte() else h
                val nFold = if (n in 'a'.code.toByte()..'z'.code.toByte()) (n - 32).toByte() else n
                if (hFold != nFold) continue@outer
            }
            return true
        }
        return false
    }
}
