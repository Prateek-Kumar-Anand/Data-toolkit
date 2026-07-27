package com.prateek.datatoolkit.features.ocr

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.prateek.datatoolkit.core.cache.CacheManager
import com.prateek.datatoolkit.core.export.DocxWriter
import com.prateek.datatoolkit.core.quality.QualityScorer
import com.prateek.datatoolkit.databinding.ActivityOcrBinding
import com.prateek.datatoolkit.features.excel.ExcelCsvHelper
import com.prateek.datatoolkit.features.pdf.PdfHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class OcrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOcrBinding
    private lateinit var cache: CacheManager
    private var cameraImageUri: Uri? = null

    // Text recognized so far, one entry per page processed (single image = 1 entry).
    // Kept around so every export format (TXT/PDF/DOCX/XLSX) can re-use the same result.
    private var pageTexts: List<String> = emptyList()
    private var lastDurationMs: Long = 0

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraImageUri != null) loadAndRecognize(listOf(cameraImageUri!!))
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadAndRecognize(listOf(uri))
    }

    // Multi-page OCR: each picked image is treated as one page, processed in order.
    private val pickMultipleImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) loadAndRecognize(uris)
    }

    // Browse-to-save: opens the system file picker so the user chooses exactly
    // where the recognized text is written (any folder, any storage provider).
    private val saveTextAs = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { writeTo(it) { file -> file.writeText(combinedText()) } }
    }
    private val savePdfAs = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { writeTo(it) { file -> PdfHelper.textToPdf(combinedText(), file) } }
    }
    private val saveDocxAs = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    ) { uri ->
        uri?.let { writeTo(it) { file -> DocxWriter.writeText(combinedText(), file) } }
    }
    private val saveXlsxAs = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        uri?.let { writeTo(it) { file -> ExcelCsvHelper.writeXlsx(pageTextsToRows(), file, sheetName = "OCR Text") } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOcrBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cache = CacheManager(this)

        binding.btnCamera.setOnClickListener { launchCamera() }
        binding.btnGallery.setOnClickListener { pickImage.launch("image/*") }
        binding.btnMultiPage.setOnClickListener { pickMultipleImages.launch("image/*") }

        binding.btnSaveText.setOnClickListener { saveAs(saveTextAs, "ocr_${System.currentTimeMillis()}.txt") }
        binding.btnSavePdf.setOnClickListener { saveAs(savePdfAs, "ocr_${System.currentTimeMillis()}.pdf") }
        binding.btnSaveDocx.setOnClickListener { saveAs(saveDocxAs, "ocr_${System.currentTimeMillis()}.docx") }
        binding.btnSaveXlsx.setOnClickListener { saveAs(saveXlsxAs, "ocr_${System.currentTimeMillis()}.xlsx") }
    }

    private fun launchCamera() {
        val file = File(cacheDir, "ocr_capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        cameraImageUri = uri
        takePicture.launch(uri)
    }

    private fun loadAndRecognize(uris: List<Uri>) {
        setExportEnabled(false)
        binding.progressBar.max = uris.size
        binding.progressBar.progress = 0
        binding.tvSummary.text = ""
        binding.tvStatus.text = if (uris.size == 1) "Recognizing text..." else "Processing page 1 of ${uris.size} (0%)..."

        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            try {
                val bitmaps = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                    }
                }
                if (bitmaps.isEmpty()) throw IllegalStateException("Could not decode any selected image")

                binding.ivPreview.setImageBitmap(bitmaps.first())

                val results = OcrHelper.recognizeBatch(bitmaps) { done, total ->
                    val pct = (done * 100) / total
                    binding.progressBar.max = total
                    binding.progressBar.progress = done
                    binding.tvStatus.text = "Processing page $done of $total ($pct%)..."
                }

                pageTexts = results.map { it.text }
                lastDurationMs = System.currentTimeMillis() - start
                binding.etResult.setText(combinedText())

                val quality = QualityScorer.scoreText(combinedText())
                val charCount = pageTexts.sumOf { it.length }
                val wordCount = pageTexts.sumOf { p -> p.split(Regex("\\s+")).count { it.isNotBlank() } }

                binding.tvStatus.text = "Done: ${bitmaps.size} page(s) processed  |  Quality: $quality/100 (${QualityScorer.label(quality)})"
                binding.tvSummary.text = "Extracted text: $charCount characters ($wordCount words) across ${bitmaps.size} page(s)  |  " +
                    "Time taken: ${formatDuration(lastDurationMs)}"

                val bytes = java.io.ByteArrayOutputStream().also {
                    bitmaps.first().compress(Bitmap.CompressFormat.JPEG, 90, it)
                }.toByteArray()
                cache.record(
                    feature = "OCR",
                    inputBytes = bytes,
                    inputLabel = if (uris.size == 1) (uris.first().lastPathSegment ?: "image") else "${uris.size} images",
                    outputPreview = combinedText(),
                    outputPath = null,
                    qualityScore = quality,
                    status = "SUCCESS",
                    durationMs = lastDurationMs
                )
                setExportEnabled(true)
            } catch (e: Exception) {
                binding.tvStatus.text = "OCR failed: ${e.message}"
                cache.record(
                    feature = "OCR",
                    inputText = uris.joinToString(",") { it.toString() },
                    inputLabel = uris.firstOrNull()?.lastPathSegment ?: "image",
                    outputPreview = "Failed: ${e.message}",
                    outputPath = null,
                    qualityScore = 0,
                    status = "FAILED"
                )
            }
        }
    }

    private fun combinedText(): String =
        if (pageTexts.size <= 1) pageTexts.firstOrNull().orEmpty()
        else pageTexts.mapIndexed { i, t -> "--- Page ${i + 1} ---\n$t" }.joinToString("\n\n")

    private fun pageTextsToRows(): List<List<String>> =
        listOf(listOf("Page", "Text")) + pageTexts.mapIndexed { i, t -> listOf((i + 1).toString(), t) }

    private fun formatDuration(ms: Long): String =
        if (ms < 1000) "${ms}ms" else "%.1fs".format(ms / 1000.0)

    private fun setExportEnabled(enabled: Boolean) {
        binding.btnSaveText.isEnabled = enabled
        binding.btnSavePdf.isEnabled = enabled
        binding.btnSaveDocx.isEnabled = enabled
        binding.btnSaveXlsx.isEnabled = enabled
    }

    private fun <T> saveAs(launcher: androidx.activity.result.ActivityResultLauncher<T>, name: T) {
        if (pageTexts.isEmpty() || combinedText().isBlank()) {
            Toast.makeText(this, "Nothing to save yet", Toast.LENGTH_SHORT).show()
            return
        }
        launcher.launch(name)
    }

    private fun writeTo(uri: Uri, write: (File) -> Unit) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val temp = File.createTempFile("ocr_export_", ".tmp", cacheDir)
                    write(temp)
                    contentResolver.openOutputStream(uri)?.use { out ->
                        temp.inputStream().use { input -> input.copyTo(out) }
                    } ?: throw IllegalStateException("Could not open destination for writing")
                    temp.delete()
                }
                Toast.makeText(this@OcrActivity, "Saved", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@OcrActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
