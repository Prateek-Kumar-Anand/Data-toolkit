package com.prateek.datatoolkit.core.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Every feature's "export"/"save" action funnels through here instead of writing wherever it
 * likes. All output lands under the public Downloads directory at Downloads/Output/<module>/,
 * one auto-created subfolder per module (see [Module]), and never overwrites an existing file -
 * a name collision gets " (1)", " (2)", etc. appended, the same convention Android's own
 * Downloads/Files apps use (see [uniqueName]).
 *
 * Two write paths depending on API level - both intentionally avoid ever requesting
 * WRITE_EXTERNAL_STORAGE on API 29+, where it no longer does anything useful under scoped
 * storage and would just be a permission the app doesn't need:
 *  - API 29+ (Q+): inserted through the MediaStore.Downloads collection with RELATIVE_PATH set
 *    to the module subfolder. This is the only way to place a file under a shared/public
 *    directory once scoped storage applies, and needs no permission at all for entries the app
 *    itself creates.
 *  - API 24-28 (this app's minSdk, pre-scoped-storage): scoped storage doesn't apply yet, so
 *    this writes straight to the filesystem path under the public Downloads directory. Needs
 *    the legacy WRITE_EXTERNAL_STORAGE permission, requested at the call site via
 *    [StoragePermissionHelper] before this is ever reached.
 */
object OutputStorage {

    /** One entry per module folder this app writes into. Folder names match what the user
     *  sees under Downloads/Output/ exactly - add new modules here, never inline a folder
     *  name string at a call site. */
    enum class Module(val folderName: String) {
        WEB_SCRAPING("Web_Scraping"),
        DATA_CLEANING("Data_Cleaning"),
        OCR("OCR"),
        INVOICES("Invoices"),
        PDF("PDF"),
        EMAIL_EXTRACTION("Email_Extraction"),
        EXCEL("Excel"),
        BATCH("Batch")
    }

    /** [uri] is the MediaStore content:// uri on API 29+, or a file:// uri on API 24-28 -
     *  either way, the thing that was actually just written. [humanPath] is always the
     *  Downloads-relative path, suitable for showing directly in a Toast/status line. */
    data class SavedFile(val uri: Uri, val displayName: String, val humanPath: String)

    private const val BASE_FOLDER = "Output"

    /** MediaStore RELATIVE_PATH / legacy sub-path under Downloads for [module], e.g.
     *  "Download/Output/Excel/" - always relative to [Environment.DIRECTORY_DOWNLOADS], since
     *  MediaStore.Downloads rejects any RELATIVE_PATH that doesn't start there. */
    private fun relativePath(module: Module): String =
        "${Environment.DIRECTORY_DOWNLOADS}/$BASE_FOLDER/${module.folderName}/"

    /** Copies [source]'s bytes into Downloads/Output/<module>/ under a unique name derived
     *  from [desiredName]. [source] itself is left untouched - callers that used it as a
     *  scratch/temp file still own deleting it afterwards, same as before this existed. */
    fun saveFile(context: Context, module: Module, source: File, desiredName: String, mimeType: String): SavedFile =
        write(context, module, desiredName, mimeType) { out -> source.inputStream().use { it.copyTo(out) } }

    /** Same as [saveFile], for callers that already have the output in memory rather than on
     *  disk (e.g. a CSV/plain-text string). */
    fun saveBytes(context: Context, module: Module, bytes: ByteArray, desiredName: String, mimeType: String): SavedFile =
        write(context, module, desiredName, mimeType) { out -> out.write(bytes) }

    private fun write(context: Context, module: Module, desiredName: String, mimeType: String, writer: (OutputStream) -> Unit): SavedFile =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(context, module, desiredName, mimeType, writer)
        } else {
            writeViaLegacyFile(context, module, desiredName, mimeType, writer)
        }

    // ---- API 29+ : MediaStore.Downloads -------------------------------------------------------

    private fun writeViaMediaStore(context: Context, module: Module, desiredName: String, mimeType: String, writer: (OutputStream) -> Unit): SavedFile {
        val resolver = context.contentResolver
        val relPath = relativePath(module)
        val (base, ext) = splitName(desiredName)
        val finalName = uniqueName(base, ext, existingDisplayNames(context, relPath))

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create $finalName under Downloads/$BASE_FOLDER/${module.folderName}")
        try {
            resolver.openOutputStream(itemUri)?.use { out -> writer(out) }
                ?: throw IOException("Could not open $finalName for writing")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null) // don't leave a half-written/pending row behind
            throw e
        }
        return SavedFile(itemUri, finalName, "Downloads/$BASE_FOLDER/${module.folderName}/$finalName")
    }

    /** Every display name already sitting in [relPath]. MediaStore.Downloads doesn't reliably
     *  reject or dedupe a repeated DISPLAY_NAME the same way across every OEM/API level, so this
     *  checks ourselves rather than trusting the provider not to produce two files that look
     *  like an overwrite to the user. */
    private fun existingDisplayNames(context: Context, relPath: String): Set<String> {
        val names = mutableSetOf<String>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(relPath),
            null
        )?.use { cursor ->
            val col = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) names.add(cursor.getString(col))
        }
        return names
    }

    // ---- API 24-28 : direct filesystem access to the public Downloads dir --------------------

    // Environment.getExternalStoragePublicDirectory has been deprecated since API 29 in favor of
    // MediaStore/scoped storage - which is exactly the path used above for API 29+. It's kept
    // here deliberately, guarded by the SDK_INT check in write(), as the correct pre-scoped-
    // storage way to reach the public Downloads folder; there's no MediaStore.Downloads
    // collection to fall back to on these API levels.
    @Suppress("DEPRECATION")
    private fun writeViaLegacyFile(context: Context, module: Module, desiredName: String, mimeType: String, writer: (OutputStream) -> Unit): SavedFile {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$BASE_FOLDER/${module.folderName}")
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw IOException("Could not create folder ${dir.absolutePath}")
        }
        val (base, ext) = splitName(desiredName)
        val finalName = uniqueName(base, ext, dir.list()?.toSet() ?: emptySet())
        val target = File(dir, finalName)
        target.outputStream().use { out -> writer(out) }

        // So the file shows up immediately in Files/Downloads apps instead of waiting for the
        // next full-device media scan.
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)

        return SavedFile(Uri.fromFile(target), finalName, "Downloads/$BASE_FOLDER/${module.folderName}/$finalName")
    }

    // ---- shared naming helpers -----------------------------------------------------------------

    private fun splitName(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    }

    /** "$base$ext" if that's not already in [existing]; otherwise "base (1)ext", "base (2)ext",
     *  ... until one is free. Mirrors Android's own Downloads naming convention, so an export
     *  never silently replaces a file that's already there. */
    private fun uniqueName(base: String, ext: String, existing: Set<String>): String {
        if ("$base$ext" !in existing) return "$base$ext"
        var n = 1
        while ("$base ($n)$ext" in existing) n++
        return "$base ($n)$ext"
    }
}
