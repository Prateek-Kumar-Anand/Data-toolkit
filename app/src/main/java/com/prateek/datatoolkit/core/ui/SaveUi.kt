package com.prateek.datatoolkit.core.ui

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.prateek.datatoolkit.core.storage.OutputStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copies an already-built [file] into the public Downloads folder for [module] on a background
 * thread and tells the user where it went (or why it failed). Several screens had their own
 * copy of this exact launch / try / Toast block.
 */
fun ComponentActivity.saveFileWithToast(module: OutputStorage.Module, file: File, name: String, mimeType: String) {
    lifecycleScope.launch {
        try {
            val saved = withContext(Dispatchers.IO) { OutputStorage.saveFile(this@saveFileWithToast, module, file, name, mimeType) }
            Toast.makeText(this@saveFileWithToast, "Saved to ${saved.humanPath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this@saveFileWithToast, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
