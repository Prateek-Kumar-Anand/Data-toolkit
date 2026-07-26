package com.prateek.datatoolkit.features.ocr

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.prateek.datatoolkit.core.cache.CacheManager
import com.prateek.datatoolkit.core.quality.QualityScorer
import com.prateek.datatoolkit.databinding.ActivityOcrBinding
import kotlinx.coroutines.launch
import java.io.File

class OcrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOcrBinding
    private lateinit var cache: CacheManager
    private var cameraImageUri: Uri? = null
    private var currentBitmap: Bitmap? = null

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraImageUri != null) loadAndRecognize(cameraImageUri!!)
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadAndRecognize(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOcrBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cache = CacheManager(this)

        binding.btnCamera.setOnClickListener { launchCamera() }
        binding.btnGallery.setOnClickListener { pickImage.launch("image/*") }
        binding.btnSaveText.setOnClickListener { saveText() }
    }

    private fun launchCamera() {
        val file = File(cacheDir, "ocr_capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        cameraImageUri = uri
        takePicture.launch(uri)
    }

    private fun loadAndRecognize(uri: Uri) {
        binding.tvStatus.text = "Recognizing text..."
        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            try {
                val input = contentResolver.openInputStream(uri)
                val bitmap = input?.use { android.graphics.BitmapFactory.decodeStream(it) }
                    ?: throw IllegalStateException("Could not decode image")
                currentBitmap = bitmap
                binding.ivPreview.setImageBitmap(bitmap)

                val result = OcrHelper.recognize(bitmap)
                binding.etResult.setText(result.text)

                val quality = QualityScorer.scoreText(result.text)
                binding.tvStatus.text = "Blocks found: ${result.blockCount}  |  Quality: $quality/100 (${QualityScorer.label(quality)})"

                val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                cache.record(
                    feature = "OCR",
                    inputBytes = bytes,
                    inputLabel = uri.lastPathSegment ?: "image",
                    outputPreview = result.text,
                    outputPath = null,
                    qualityScore = quality,
                    status = "SUCCESS",
                    durationMs = System.currentTimeMillis() - start
                )
            } catch (e: Exception) {
                binding.tvStatus.text = "OCR failed: ${e.message}"
                cache.record(
                    feature = "OCR",
                    inputText = uri.toString(),
                    inputLabel = uri.lastPathSegment ?: "image",
                    outputPreview = "Failed: ${e.message}",
                    outputPath = null,
                    qualityScore = 0,
                    status = "FAILED"
                )
            }
        }
    }

    private fun saveText() {
        val text = binding.etResult.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "Nothing to save yet", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        val file = File(dir, "ocr_${System.currentTimeMillis()}.txt")
        file.writeText(text)
        Toast.makeText(this, "Saved to ${file.absolutePath}", Toast.LENGTH_LONG).show()
    }
}
