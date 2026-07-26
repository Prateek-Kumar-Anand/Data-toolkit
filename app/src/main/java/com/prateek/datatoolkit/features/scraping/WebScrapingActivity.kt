package com.prateek.datatoolkit.features.scraping

import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.prateek.datatoolkit.core.cache.CacheManager
import com.prateek.datatoolkit.core.quality.QualityScorer
import com.prateek.datatoolkit.databinding.ActivityWebScrapingBinding
import kotlinx.coroutines.launch
import java.io.File

class WebScrapingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebScrapingBinding
    private lateinit var cache: CacheManager
    private var lastResult: ScrapeResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebScrapingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cache = CacheManager(this)

        binding.btnScrape.setOnClickListener { runScrape() }
        binding.btnSave.setOnClickListener { saveResults() }
    }

    private fun runScrape() {
        val url = binding.etUrl.text.toString().trim()
        if (url.isBlank()) {
            Toast.makeText(this, "Enter a URL first", Toast.LENGTH_SHORT).show()
            return
        }
        val normalized = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url

        binding.tvStatus.text = "Fetching (with auto-retry)..."
        lifecycleScope.launch {
            val start = System.currentTimeMillis()

            // Smart caching: skip the network entirely if we scraped this exact URL before.
            val cached = cache.findCached("SCRAPING", normalized)
            if (cached != null) {
                binding.tvStatus.text = "Loaded from cache  |  Quality: ${cached.qualityScore}/100"
                binding.tvTitle.text = cached.inputLabel
                binding.etText.setText(cached.outputPreview)
                return@launch
            }

            try {
                val result = Scraper.scrape(normalized)
                lastResult = result
                binding.etText.setText(result.text)
                binding.tvTitle.text = result.title
                binding.tvLinks.text = buildString {
                    append("${result.links.size} links, ${result.tables.size} tables\n\n")
                    result.links.take(20).forEach { append("• $it\n") }
                }
                val quality = QualityScorer.scoreText(result.text)
                binding.tvStatus.text = "Fetched in ${result.attempts} attempt(s)  |  Quality: $quality/100 (${QualityScorer.label(quality)})"

                cache.record(
                    feature = "SCRAPING",
                    inputText = normalized,
                    inputLabel = result.title.ifBlank { normalized },
                    outputPreview = result.text,
                    outputPath = null,
                    qualityScore = quality,
                    status = "SUCCESS",
                    retryCount = result.attempts - 1,
                    durationMs = System.currentTimeMillis() - start
                )
            } catch (e: Exception) {
                binding.tvStatus.text = "Failed after retries: ${e.message}"
                cache.record(
                    feature = "SCRAPING",
                    inputText = normalized,
                    inputLabel = normalized,
                    outputPreview = "Failed: ${e.message}",
                    outputPath = null,
                    qualityScore = 0,
                    status = "FAILED"
                )
            }
        }
    }

    private fun saveResults() {
        val result = lastResult
        val text = binding.etText.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "Nothing to save yet", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        val file = File(dir, "scrape_${System.currentTimeMillis()}.txt")
        val content = buildString {
            append("URL: ${result?.url ?: binding.etUrl.text}\n")
            append("Title: ${result?.title ?: ""}\n\n")
            append(text)
            if (result != null) {
                append("\n\n--- LINKS ---\n")
                result.links.forEach { append("$it\n") }
            }
        }
        file.writeText(content)
        Toast.makeText(this, "Saved to ${file.absolutePath}", Toast.LENGTH_LONG).show()
    }
}
