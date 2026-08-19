package com.prateek.datatoolkit.features.email

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.prateek.datatoolkit.core.cache.CacheManager
import com.prateek.datatoolkit.core.quality.QualityScorer
import com.prateek.datatoolkit.core.storage.OutputStorage
import com.prateek.datatoolkit.core.storage.StoragePermissionHelper
import com.prateek.datatoolkit.databinding.ActivityEmailExtractionBinding
import com.prateek.datatoolkit.features.scraping.Scraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EmailExtractionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEmailExtractionBinding
    private lateinit var cache: CacheManager

    // Auto-save (Downloads/Output/Email_Extraction/) needs WRITE_EXTERNAL_STORAGE on API 24-28
    // only; see StoragePermissionHelper.
    private val storagePermission = StoragePermissionHelper(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmailExtractionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cache = CacheManager(this)

        binding.btnExtractFromText.setOnClickListener { extractFromText(binding.etInput.text.toString()) }
        binding.btnExtractFromUrl.setOnClickListener { extractFromUrl(binding.etInput.text.toString().trim()) }
        binding.btnSaveCsv.setOnClickListener { saveCsv() }
    }

    private fun extractFromText(text: String) {
        if (text.isBlank()) {
            Toast.makeText(this, "Paste some text first", Toast.LENGTH_SHORT).show()
            return
        }
        val result = EmailExtractor.extract(text)
        showResult(result, text, "Pasted text")
    }

    private fun extractFromUrl(url: String) {
        if (url.isBlank()) {
            Toast.makeText(this, "Enter a URL first", Toast.LENGTH_SHORT).show()
            return
        }
        val normalized = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
        binding.progressBar.visibility = View.VISIBLE
        binding.btnExtractFromUrl.isEnabled = false
        binding.tvStatus.text = "Scraping (with auto-retry)..."
        lifecycleScope.launch {
            try {
                val scraped = Scraper.scrape(normalized)
                val result = EmailExtractor.extract(scraped.text)
                showResult(result, normalized, scraped.title.ifBlank { normalized })
            } catch (e: Exception) {
                binding.tvStatus.text = "Scrape failed: ${e.message}"
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnExtractFromUrl.isEnabled = true
            }
        }
    }

    private fun showResult(result: EmailExtractor.ExtractionResult, source: String, label: String) {
        binding.etEmails.setText(result.emails.joinToString("\n"))
        val quality = QualityScorer.scoreEmailList(result.emails)
        binding.tvStatus.text = "${result.emails.size} valid emails  |  ${result.duplicatesRemoved} duplicates removed  |  " +
            "${result.rejected.size} rejected  |  Quality: $quality/100 (${QualityScorer.label(quality)})"

        lifecycleScope.launch {
            cache.record(
                feature = "EMAIL",
                inputText = source,
                inputLabel = label,
                outputPreview = result.emails.joinToString(", "),
                outputPath = null,
                qualityScore = quality,
                status = "SUCCESS"
            )
        }
    }

    private fun saveCsv() {
        val emailsText = binding.etEmails.text.toString()
        if (emailsText.isBlank()) {
            Toast.makeText(this, "Nothing to save yet", Toast.LENGTH_SHORT).show()
            return
        }
        storagePermission.runWithPermission { writeEmails() }
    }

    /** Builds the same CSV as before, then auto-saves it into
     *  Downloads/Output/Email_Extraction/ (auto-created, collision-proof name) instead of
     *  prompting the user to browse to a destination. */
    private fun writeEmails() {
        lifecycleScope.launch {
            try {
                val emails = binding.etEmails.text.toString().lines().filter { it.isNotBlank() }
                val saved = withContext(Dispatchers.IO) {
                    OutputStorage.saveBytes(
                        this@EmailExtractionActivity, OutputStorage.Module.EMAIL_EXTRACTION,
                        EmailExtractor.toCsv(emails).toByteArray(), "emails_${System.currentTimeMillis()}.csv", "text/csv"
                    )
                }
                Toast.makeText(this@EmailExtractionActivity, "Saved to ${saved.humanPath}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@EmailExtractionActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
