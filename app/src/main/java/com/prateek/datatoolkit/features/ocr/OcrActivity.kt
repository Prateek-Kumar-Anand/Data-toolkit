package com.prateek.datatoolkit.features.ocr

import com.prateek.datatoolkit.R
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.prateek.datatoolkit.core.cache.CacheManager
import com.prateek.datatoolkit.core.io.CameraCapture
import com.prateek.datatoolkit.core.image.SafeBitmap
import com.prateek.datatoolkit.core.export.DocxWriter
import com.prateek.datatoolkit.core.quality.QualityScorer
import com.prateek.datatoolkit.core.storage.OutputStorage
import com.prateek.datatoolkit.core.storage.StoragePermissionHelper
import com.prateek.datatoolkit.databinding.ActivityOcrBinding
import com.prateek.datatoolkit.features.excel.ExcelCsvHelper
import com.prateek.datatoolkit.features.pdf.PdfHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.prateek.datatoolkit.core.ui.formatDuration

class OcrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOcrBinding
    private lateinit var cache: CacheManager

    // Text recognized so far, one entry per page processed (single image = 1 entry).
    // Kept around so every export format (TXT/PDF/DOCX/XLSX) can re-use the same result.
    private var pageTexts: List<String> = emptyList()
    private var lastDurationMs: Long = 0

    private val camera = CameraCapture(this, "ocr") { uri -> loadAndRecognize(listOf(uri)) }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadAndRecognize(listOf(uri))
    }

    // Multi-page OCR: each picked image is treated as one page, processed in order.
    private val pickMultipleImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) loadAndRecognize(uris)
    }

    // Auto-save (Downloads/Output/OCR/) needs WRITE_EXTERNAL_STORAGE on API 24-28 only; see
    // StoragePermissionHelper.
    private val storagePermission = StoragePermissionHelper(this)

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOcrBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.etResult.setOnTouchListener { v, ev ->
            if (v.canScrollVertically(-1) || v.canScrollVertically(1)) {
                v.parent.requestDisallowInterceptTouchEvent(true)
                if (ev.action == android.view.MotionEvent.ACTION_UP) {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                    v.performClick()
                }
            }
            false
        }
        cache = CacheManager(this)

        binding.cbBookMode.setOnCheckedChangeListener { _, _ ->
            if (ocrResults.isNotEmpty()) {
                pageTexts = textsFor(ocrResults)
                binding.etResult.setText(buildCombined())
            }
        }

        binding.btnCamera.setOnClickListener { camera.launch() }
        binding.btnGallery.setOnClickListener { pickImage.launch("image/*") }
        binding.btnMultiPage.setOnClickListener { pickMultipleImages.launch("image/*") }

        binding.btnSaveText.setOnClickListener {
            saveAs { saveOutput("ocr_${System.currentTimeMillis()}.txt", "text/plain") { file -> file.writeText(combinedText()) } }
        }
        binding.btnSavePdf.setOnClickListener {
            saveAs { saveOutput("ocr_${System.currentTimeMillis()}.pdf", "application/pdf") { file -> if (bookMode()) PdfHelper.bookToPdf(combinedText(), file) else PdfHelper.textToPdfWithTables(combinedText(), file) } }
        }
        binding.btnSaveDocx.setOnClickListener {
            saveAs {
                saveOutput(
                    "ocr_${System.currentTimeMillis()}.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                ) { file -> if (bookMode()) DocxWriter.writeBookText(combinedText(), file) else DocxWriter.writeTextWithTables(combinedText(), file) }
            }
        }
        binding.btnSaveXlsx.setOnClickListener {
            saveAs {
                saveOutput(
                    "ocr_${System.currentTimeMillis()}.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                ) { file -> ExcelCsvHelper.writeXlsx(pageTextsToRows(), file, sheetName = "OCR Text") }
            }
        }
    }

    private fun loadAndRecognize(uris: List<Uri>) {
        setExportEnabled(false)
        binding.progressBar.max = uris.size
        binding.progressBar.progress = 0
        binding.progressBar.visibility = View.VISIBLE
        binding.tvSummary.text = ""
        binding.tvStatus.text = if (uris.size == 1) "Recognizing text..." else "Processing page 1 of ${uris.size} (0%)..."

        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            try {
                val bitmaps = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        SafeBitmap.decode(this@OcrActivity, uri)
                    }
                }
                if (bitmaps.isEmpty()) throw IllegalStateException("Could not decode any selected image")

                binding.ivPreview.setImageBitmap(bitmaps.first())

                val results = OcrHelper.recognizeBatch(bitmaps) { done, total ->
                    val pct = (done * 100) / total
                    binding.progressBar.max = total
                    binding.progressBar.progress = done
                    binding.tvStatus.text = getString(R.string.ocr_processing_page_of, done, total, pct)
                }

                ocrResults = results
                pageTexts = textsFor(results)
                lastDurationMs = System.currentTimeMillis() - start
                binding.etResult.setText(buildCombined())

                val quality = QualityScorer.scoreText(buildCombined())
                val charCount = pageTexts.sumOf { it.length }
                val wordCount = pageTexts.sumOf { p -> p.split(Regex("\\s+")).count { it.isNotBlank() } }

                binding.tvStatus.text = getString(R.string.ocr_done_page_s_processed, bitmaps.size, quality, QualityScorer.label(quality))
                binding.tvSummary.text = getString(R.string.ocr_extracted_text_characters_words, charCount, wordCount, bitmaps.size, formatDuration(lastDurationMs))

                val bytes = java.io.ByteArrayOutputStream().also {
                    bitmaps.first().compress(Bitmap.CompressFormat.JPEG, 90, it)
                }.toByteArray()
                cache.record(
                    feature = "OCR",
                    inputBytes = bytes,
                    inputLabel = if (uris.size == 1) (uris.first().lastPathSegment ?: "image") else "${uris.size} images",
                    outputPreview = buildCombined(),
                    outputPath = null,
                    qualityScore = quality,
                    status = "SUCCESS",
                    durationMs = lastDurationMs
                )
                setExportEnabled(true)
            } catch (e: Exception) {
                binding.tvStatus.text = getString(R.string.ocr_ocr_failed, e.message)
                cache.record(
                    feature = "OCR",
                    inputText = uris.joinToString(",") { it.toString() },
                    inputLabel = uris.firstOrNull()?.lastPathSegment ?: "image",
                    outputPreview = "Failed: ${e.message}",
                    outputPath = null,
                    qualityScore = 0,
                    status = "FAILED"
                )
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    /** What exports use: the editor's current content, so user edits are kept. */
    private fun combinedText(): String =
        binding.etResult.text?.toString()?.takeIf { it.isNotBlank() } ?: buildCombined()

    private var ocrResults: List<OcrResult> = emptyList()

    private fun bookMode() = binding.cbBookMode.isChecked

    private fun textsFor(rs: List<OcrResult>): List<String> = rs.map {
        (if (bookMode()) it.bookText else it.layoutText).ifBlank { it.text }
    }

    private fun buildCombined(): String =
        if (bookMode()) OcrBook.joinPages(pageTexts)
        else if (pageTexts.size <= 1) pageTexts.firstOrNull().orEmpty()
        else pageTexts.mapIndexed { i, t -> "--- Page ${i + 1} ---\n$t" }.joinToString("\n\n")

    /** One sheet row per text line; tab-separated table cells become separate columns. */
    private fun pageTextsToRows(): List<List<String>> {
        val rows = combinedText().replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n').split("\n").map { it.split('\t') }
        val cols = rows.maxOfOrNull { it.size } ?: 1
        return rows.map { r -> r + List(cols - r.size) { "" } }
    }



    private fun setExportEnabled(enabled: Boolean) {
        binding.btnSaveText.isEnabled = enabled
        binding.btnSavePdf.isEnabled = enabled
        binding.btnSaveDocx.isEnabled = enabled
        binding.btnSaveXlsx.isEnabled = enabled
    }

    private fun saveAs(action: () -> Unit) {
        if (pageTexts.isEmpty() || combinedText().isBlank()) {
            Toast.makeText(this, "Nothing to save yet", Toast.LENGTH_SHORT).show()
            return
        }
        storagePermission.runWithPermission(action)
    }

    /** Builds the export exactly as before via [write], then auto-saves it into
     *  Downloads/Output/OCR/ (auto-created, collision-proof name) instead of prompting the
     *  user to browse to a destination. */
    private fun saveOutput(name: String, mimeType: String, write: (File) -> Unit) {
        lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    OutputStorage.saveViaTemp(this@OcrActivity, OutputStorage.Module.OCR, name, mimeType, write)
                }
                Toast.makeText(this@OcrActivity, "Saved to ${saved.humanPath}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@OcrActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
