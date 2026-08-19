package com.prateek.datatoolkit.features.pdf

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.prateek.datatoolkit.core.cache.CacheManager
import com.prateek.datatoolkit.core.quality.QualityScorer
import com.prateek.datatoolkit.core.storage.OutputStorage
import com.prateek.datatoolkit.core.storage.StoragePermissionHelper
import com.prateek.datatoolkit.databinding.ActivityPdfBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PdfActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfBinding
    private lateinit var cache: CacheManager

    // The most recently produced PDF file (merge/split/images-to-pdf), kept in
    // app-private cache until the user taps Save, which auto-saves it into
    // Downloads/Output/PDF/.
    private var lastPdfFile: File? = null

    private val pickOnePdf = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { pendingAction?.invoke(listOf(it)) }
    }
    private val pickManyPdfs = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) pendingAction?.invoke(uris)
    }
    private val pickManyImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) pendingAction?.invoke(uris)
    }

    /** Holds whichever picker's callback should run next - set right before launching a picker. */
    private var pendingAction: ((List<Uri>) -> Unit)? = null

    // Auto-save (Downloads/Output/PDF/) needs WRITE_EXTERNAL_STORAGE on API 24-28 only; see
    // StoragePermissionHelper.
    private val storagePermission = StoragePermissionHelper(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cache = CacheManager(this)

        binding.btnExtractText.setOnClickListener {
            pendingAction = { uris -> extractText(uris.first()) }
            pickOnePdf.launch("application/pdf")
        }
        binding.btnMerge.setOnClickListener {
            pendingAction = { uris -> mergePdfs(uris) }
            pickManyPdfs.launch("application/pdf")
        }
        binding.btnSplit.setOnClickListener {
            pendingAction = { uris -> splitPdf(uris.first()) }
            pickOnePdf.launch("application/pdf")
        }
        binding.btnImagesToPdf.setOnClickListener {
            pendingAction = { uris -> imagesToPdf(uris) }
            pickManyImages.launch("image/*")
        }
        binding.btnSaveAs.setOnClickListener { onSaveAsClicked() }
    }

    private fun copyUriToTempFile(uri: Uri, suffix: String): File {
        val file = File.createTempFile("pdf_", suffix, cacheDir)
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        }
        return file
    }

    /** Drives the progress bar and disables every action button while one operation runs,
     * so a second tap can't start an overlapping job. */
    private fun setBusy(busy: Boolean) {
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnExtractText.isEnabled = !busy
        binding.btnMerge.isEnabled = !busy
        binding.btnSplit.isEnabled = !busy
        binding.btnImagesToPdf.isEnabled = !busy
        binding.btnSaveAs.isEnabled = !busy
    }

    private fun extractText(uri: Uri) {
        setBusy(true)
        binding.tvStatus.text = "Extracting text..."
        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            try {
                val text = withContext(Dispatchers.IO) {
                    val file = copyUriToTempFile(uri, ".pdf")
                    val result = PdfHelper.extractText(file)
                    file.delete()
                    result
                }
                lastPdfFile = null // this result is text, not a PDF file
                binding.etOutput.setText(text)
                val quality = QualityScorer.scoreText(text)
                binding.tvStatus.text = "Extracted ${text.length} characters  |  Quality: $quality/100"
                cache.record("PDF", text.toByteArray(), uri.lastPathSegment ?: "pdf", text, null, quality, "SUCCESS", durationMs = System.currentTimeMillis() - start)
            } catch (e: Exception) {
                binding.tvStatus.text = "Failed: ${e.message}"
            } finally {
                setBusy(false)
            }
        }
    }

    private fun mergePdfs(uris: List<Uri>) {
        if (uris.size < 2) {
            Toast.makeText(this, "Pick at least 2 PDFs", Toast.LENGTH_SHORT).show()
            return
        }
        setBusy(true)
        binding.tvStatus.text = "Merging ${uris.size} PDFs..."
        lifecycleScope.launch {
            try {
                val outFile = File(cacheDir, "merged_${System.currentTimeMillis()}.pdf")
                withContext(Dispatchers.IO) {
                    val files = uris.map { copyUriToTempFile(it, ".pdf") }
                    PdfHelper.merge(files, outFile)
                    files.forEach { it.delete() }
                }
                lastPdfFile = outFile
                binding.tvStatus.text = "Merged ${uris.size} PDFs — tap Save to save it"
                binding.etOutput.setText("Ready: ${outFile.name}\n\nTap \"Save Last Result\" below to save this PDF to Downloads/Output/PDF/.")
                cache.record("PDF", outFile.readBytes(), "${uris.size} PDFs merged", outFile.name, null, 100, "SUCCESS")
            } catch (e: Exception) {
                binding.tvStatus.text = "Merge failed: ${e.message}"
            } finally {
                setBusy(false)
            }
        }
    }

    private fun splitPdf(uri: Uri) {
        val start = binding.etStartPage.text.toString().toIntOrNull()
        val end = binding.etEndPage.text.toString().toIntOrNull()
        if (start == null || end == null || start < 1 || end < start) {
            Toast.makeText(this, "Enter a valid page range first", Toast.LENGTH_SHORT).show()
            return
        }
        setBusy(true)
        binding.tvStatus.text = "Splitting pages $start-$end..."
        lifecycleScope.launch {
            try {
                val outFile = File(cacheDir, "split_${start}_${end}_${System.currentTimeMillis()}.pdf")
                withContext(Dispatchers.IO) {
                    val file = copyUriToTempFile(uri, ".pdf")
                    PdfHelper.splitRange(file, start, end, outFile)
                    file.delete()
                }
                lastPdfFile = outFile
                binding.tvStatus.text = "Split ready — tap Save to save it"
                binding.etOutput.setText("Ready: ${outFile.name}\n\nTap \"Save Last Result\" below to save this PDF to Downloads/Output/PDF/.")
                cache.record("PDF", outFile.readBytes(), uri.lastPathSegment ?: "pdf", outFile.name, null, 100, "SUCCESS")
            } catch (e: Exception) {
                binding.tvStatus.text = "Split failed: ${e.message}"
            } finally {
                setBusy(false)
            }
        }
    }

    private fun imagesToPdf(uris: List<Uri>) {
        setBusy(true)
        binding.tvStatus.text = "Building PDF from ${uris.size} images..."
        lifecycleScope.launch {
            try {
                val outFile = File(cacheDir, "images_${System.currentTimeMillis()}.pdf")
                withContext(Dispatchers.IO) {
                    val bitmaps = uris.mapNotNull { uri ->
                        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    }
                    PdfHelper.imagesToPdf(bitmaps, outFile)
                }
                lastPdfFile = outFile
                binding.tvStatus.text = "Built from ${uris.size} images — tap Save to save it"
                binding.etOutput.setText("Ready: ${outFile.name}\n\nTap \"Save Last Result\" below to save this PDF to Downloads/Output/PDF/.")
                cache.record("PDF", outFile.readBytes(), "${uris.size} images", outFile.name, null, 100, "SUCCESS")
            } catch (e: Exception) {
                binding.tvStatus.text = "Failed: ${e.message}"
            } finally {
                setBusy(false)
            }
        }
    }

    /** Routes to the right auto-save depending on whether the last result was a PDF file or
     *  plain text - both now land straight in Downloads/Output/PDF/ instead of prompting the
     *  user to browse to a destination. */
    private fun onSaveAsClicked() {
        val pdf = lastPdfFile
        if (pdf != null && pdf.exists()) {
            storagePermission.runWithPermission { savePdfResult(pdf) }
            return
        }
        val text = binding.etOutput.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "Nothing to save yet — run an operation above first", Toast.LENGTH_SHORT).show()
            return
        }
        storagePermission.runWithPermission { saveTextResult(text) }
    }

    private fun savePdfResult(file: File) {
        lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    OutputStorage.saveFile(this@PdfActivity, OutputStorage.Module.PDF, file, file.name, "application/pdf")
                }
                Toast.makeText(this@PdfActivity, "Saved to ${saved.humanPath}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@PdfActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveTextResult(text: String) {
        lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    OutputStorage.saveBytes(
                        this@PdfActivity, OutputStorage.Module.PDF, text.toByteArray(),
                        "extracted_${System.currentTimeMillis()}.txt", "text/plain"
                    )
                }
                Toast.makeText(this@PdfActivity, "Saved to ${saved.humanPath}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@PdfActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
