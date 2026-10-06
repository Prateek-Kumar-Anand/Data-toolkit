package com.prateek.datatoolkit.core.ui

import android.content.Context

/** dp -> px for views that are built in code. Shared instead of a private copy per screen. */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/** "850ms" / "2.4s" - how long a run took. */
fun formatDuration(ms: Long): String = if (ms < 1000) "${ms}ms" else "%.1fs".format(ms / 1000.0)

/** "512 B" / "3.4 KB" / "1.2 MB". */
fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
