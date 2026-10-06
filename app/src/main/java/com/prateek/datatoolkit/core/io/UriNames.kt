package com.prateek.datatoolkit.core.io

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * The file name a picked/shared [uri] is shown as (never a path): the provider's display name,
 * falling back to the last path segment, then to [fallback]. One shared copy - every screen that
 * lets the user pick a file used to carry its own near-identical version.
 */
fun Context.displayNameOf(uri: Uri, fallback: String = "file"): String {
    var name: String? = null
    try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = c.getString(idx)
            }
        }
    } catch (_: Exception) {
        // Fall through to the path-based fallbacks below.
    }
    return (name ?: uri.lastPathSegment ?: fallback).substringAfterLast('/').ifBlank { fallback }
}
