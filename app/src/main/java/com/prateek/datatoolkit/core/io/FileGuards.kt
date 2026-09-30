package com.prateek.datatoolkit.core.io

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Size-capped file/URI reads so a huge or malicious file can't exhaust storage or memory. */
object FileGuards {

    const val MAX_DOCUMENT_BYTES = 100L * 1024 * 1024
    const val MAX_IMAGE_BYTES = 40L * 1024 * 1024
    const val MAX_TEXT_BYTES = 20L * 1024 * 1024
    const val MAX_MEDIA_BYTES = 1024L * 1024 * 1024

    class FileTooLargeException(limit: Long) :
        IOException("This file is larger than the ${limit / (1024 * 1024)} MB limit")

    fun copyLimited(input: InputStream, output: OutputStream, maxBytes: Long) {
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw FileTooLargeException(maxBytes)
            output.write(buf, 0, n)
        }
    }

    /** Copies [uri] into a new temp file (deleted again if the copy fails or is too large). */
    fun copyToTemp(
        context: Context, uri: Uri, prefix: String, suffix: String,
        maxBytes: Long = MAX_DOCUMENT_BYTES, dir: File = context.cacheDir
    ): File {
        val file = File.createTempFile(prefix.padEnd(3, '_'), safeSuffix(suffix), dir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Could not open the selected file")
            input.use { i -> file.outputStream().use { o -> copyLimited(i, o, maxBytes) } }
            return file
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
    }

    /** Reads [uri] as text (BOM-aware, UTF-8 first, falling back to Windows-1252). */
    fun readText(context: Context, uri: Uri, maxBytes: Long = MAX_TEXT_BYTES): String {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open the selected file")
        val bytes = input.use {
            val out = ByteArrayOutputStream()
            copyLimited(it, out, maxBytes)
            out.toByteArray()
        }
        return decodeText(bytes)
    }

    fun readText(file: File, maxBytes: Long = MAX_TEXT_BYTES): String {
        if (file.length() > maxBytes) throw FileTooLargeException(maxBytes)
        return decodeText(file.readBytes())
    }

    fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, java.nio.charset.Charset.forName("windows-1252"))
        }
    }

    /** Only lets a short alphanumeric extension through into a temp-file name. */
    fun safeSuffix(suffix: String): String {
        val ext = suffix.removePrefix(".")
        return if (ext.isNotEmpty() && ext.length <= 8 && ext.all { it.isLetterOrDigit() }) ".$ext" else ".tmp"
    }
}
