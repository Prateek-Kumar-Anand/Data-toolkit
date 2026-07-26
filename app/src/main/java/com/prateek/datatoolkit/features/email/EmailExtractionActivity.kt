package com.prateek.datatoolkit.features.email

import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.prateek.datatoolkit.core.cache.CacheManager
import com.prateek.datatoolkit.core.quality.QualityScorer
import com.prateek.datatoolkit.databinding.ActivityEmailExtractionBinding
import com.prateek.datatoolkit.features.scraping.Scraper
import kotlinx.coroutines.launch
import java.io.File

class EmailExtractionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEmailExtractionBinding
    private lateinit var cache: CacheManager

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
        binding.tvStatus.text = "Scraping (with auto-retry)..."
        lifecycleScope.launch {
            try {
                val scraped = Scraper.scrape(normalized)
                val result = EmailExtractor.extract(scraped.text)
                showResult(result, normalized, scraped.title.ifBlank { normalized })
            } catch (e: Exception) {
                binding.tvStatus.text = "Scrape failed: ${e.message}"
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
        val emails = emailsText.lines().filter { it.isNotBlank() }
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        val file = File(dir, "emails_${System.currentTimeMillis()}.csv")
        file.writeText(EmailExtractor.toCsv(emails))
        Toast.makeText(this, "Saved to ${file.absolutePath}", Toast.LENGTH_LONG).show()
    }
}
