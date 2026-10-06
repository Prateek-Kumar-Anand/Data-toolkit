package com.prateek.datatoolkit.core.io

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * "Take a photo" in one object: asks for the CAMERA permission when needed, creates the target
 * file in the app's private cache (shared only through the FileProvider's `camera/` folder),
 * launches the camera app and hands the resulting [Uri] to [onPhoto]. Before this existed, OCR
 * and Invoice OCR each had their own copy - and the Invoice one never asked for the permission,
 * which crashes with a SecurityException on any device where it hasn't been granted yet.
 *
 * Must be created as a field of the Activity (registerForActivityResult rules).
 */
class CameraCapture(
    private val activity: ComponentActivity,
    private val filePrefix: String,
    private val onPhoto: (Uri) -> Unit
) {
    private var pendingUri: Uri? = null

    private val takePicture = activity.registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingUri
        pendingUri = null
        if (success && uri != null) onPhoto(uri)
    }

    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start()
        else Toast.makeText(activity, "Camera permission is required to take a photo", Toast.LENGTH_LONG).show()
    }

    fun launch() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            start()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun start() {
        try {
            val dir = File(activity.cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "${filePrefix}_capture_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            pendingUri = uri
            takePicture.launch(uri)
        } catch (e: Exception) {
            pendingUri = null
            Toast.makeText(activity, "Camera unavailable: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
