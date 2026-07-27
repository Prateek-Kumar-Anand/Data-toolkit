package com.prateek.datatoolkit.features.scraping

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.prateek.datatoolkit.core.cache.CacheManager
import com.prateek.datatoolkit.core.quality.QualityScorer
import com.prateek.datatoolkit.databinding.ActivityWebScrapingBinding
import com.prateek.datatoolkit.features.excel.ExcelCsvHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WebScrapingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebScrapingBinding
    private lateinit var cache: CacheManager
    private var lastResult: ScrapeResult? = null

    // All structured items detected across every URL scraped in the last run,
    // paired with the page they came from - this is what gets auto-exported to Excel.
    private var lastItems: List<ScrapedItem> = emptyList()
    private var lastItemSources: List<String> = emptyList()

    // The .xlsx built automatically as soon as scraping finishes, waiting for
    // the user to choose where to save it via the system picker.
    private var pendingXlsxFile: File? = null

    // Browse-to-save: system file picker lets the user choose the destination.
    private val saveTextAs = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { writeResultsTo(it) }
    }
    private val saveXlsxAs = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        uri?.let { copyPendingXlsxTo(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebScrapingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cache = CacheManager(this)

        binding.btnScrape.setOnClickListener { runScrape() }
        binding.btnSave.setOnClickListener { saveResults() }
        binding.btnSaveXlsx.setOnClickListener { saveXlsx() }
    }

    /** Splits the URL box into one or more targets - one URL per line (or comma-separated). */
    private fun parseUrls(raw: String): List<String> =
        raw.split("\n", ",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (!it.startsWith("http://") && !it.startsWith("https://")) "https://$it" else it }
            .distinct()

    private fun runScrape() {
        val urls = parseUrls(binding.etUrl.text.toString())
        if (urls.isEmpty()) {
            Toast.makeText(this, "Enter at least one URL first", Toast.LENGTH_SHORT).show()
            return
        }

        setBusy(true)
        binding.progressBar.max = urls.size
        binding.progressBar.progress = 0
        binding.tvStatus.text = "Starting..."
        binding.tvItemsPreview.text = ""

        lifecycleScope.launch {
            val allItems = mutableListOf<ScrapedItem>()
            val allSources = mutableListOf<String>()
            var pagesOk = 0
            var cacheHits = 0
            var lastGoodResult: ScrapeResult? = null
            val overallStart = System.currentTimeMillis()

            for ((index, url) in urls.withIndex()) {
                val pct = ((index) * 100) / urls.size
                binding.tvStatus.text = "Scraping page ${index + 1} of ${urls.size} ($pct%)  •  ${allItems.size} item(s) found so far"
                val start = System.currentTimeMillis()

                // Smart caching: skip the network entirely if we scraped this exact URL before.
                val cached = cache.findCached("SCRAPING", url)
                if (cached != null) {
                    cacheHits++
                    binding.progressBar.progress = index + 1
                    if (urls.size == 1) {
                        binding.tvTitle.text = cached.inputLabel
                        binding.etText.setText(cached.outputPreview)
                        binding.tvStatus.text = "Loaded from cache  |  Quality: ${cached.qualityScore}/100"
                    }
                    continue
                }

                try {
                    val result = Scraper.scrape(url)
                    lastResult = result
                    lastGoodResult = result
                    pagesOk++
                    allItems += result.items
                    repeat(result.items.size) { allSources += url }

                    if (urls.size == 1 || index == urls.lastIndex) {
                        binding.etText.setText(result.text)
                        binding.tvTitle.text = result.title
                        binding.tvLinks.text = buildString {
                            append("${result.links.size} links, ${result.tables.size} tables\n\n")
                            result.links.take(20).forEach { append("• $it\n") }
                        }
                    }

                    val quality = QualityScorer.scoreText(result.text)
                    cache.record(
                        feature = "SCRAPING",
                        inputText = url,
                        inputLabel = result.title.ifBlank { url },
                        outputPreview = result.text,
                        outputPath = null,
                        qualityScore = quality,
                        status = "SUCCESS",
                        retryCount = result.attempts - 1,
                        durationMs = System.currentTimeMillis() - start
                    )
                } catch (e: Exception) {
                    cache.record(
                        feature = "SCRAPING",
                        inputText = url,
                        inputLabel = url,
                        outputPreview = "Failed: ${e.message}",
                        outputPath = null,
                        qualityScore = 0,
                        status = "FAILED"
                    )
                }

                binding.progressBar.progress = index + 1
            }

            lastItems = allItems
            lastItemSources = allSources

            val totalMs = System.currentTimeMillis() - overallStart
            binding.tvStatus.text = if (pagesOk == 0 && cacheHits == 0) {
                "Failed to scrape ${urls.size} page(s) after retries"
            } else {
                val cacheNote = if (cacheHits > 0) "  •  $cacheHits from cache" else ""
                "Done: ${pagesOk + cacheHits}/${urls.size} page(s) ready in ${totalMs}ms$cacheNote  •  ${allItems.size} item(s) detected  •  " +
                    "Quality: ${lastGoodResult?.let { QualityScorer.scoreText(it.text) } ?: 0}/100"
            }

            binding.tvItemsPreview.text = if (allItems.isEmpty()) {
                "No structured product/article cards auto-detected on this page - raw text, links and tables are still available above."
            } else {
                buildString {
                    append("Detected fields: Name, Description, Price, Rating, Link, Image, Category\n\n")
                    allItems.take(10).forEachIndexed { i, item ->
                        append("${i + 1}. ${item.name ?: "(no name)"}")
                        if (!item.price.isNullOrBlank()) append("  —  ${item.price}")
                        if (!item.rating.isNullOrBlank()) append("  •  ★ ${item.rating}")
                        append("\n")
                    }
                    if (allItems.size > 10) append("… and ${allItems.size - 10} more (see the exported Excel file)")
                }
            }

            buildAutoXlsx(allItems, allSources)
            setBusy(false)
        }
    }

    /** Builds the .xlsx as soon as scraping finishes, ready to hand off via "Save As...". */
    private fun buildAutoXlsx(items: List<ScrapedItem>, sources: List<String>) {
        lifecycleScope.launch {
            try {
                val rows = if (items.isNotEmpty()) {
                    ItemExtractor.toRows(items, includeSourceColumn = true, sources = sources)
                } else {
                    // Fallback schema when no structured cards were detected: one row per page.
                    val r = lastResult
                    listOf(
                        listOf("Title", "Source URL", "Links Found", "Tables Found", "Text Preview"),
                        listOf(
                            r?.title.orEmpty(),
                            r?.url.orEmpty(),
                            (r?.links?.size ?: 0).toString(),
                            (r?.tables?.size ?: 0).toString(),
                            r?.text?.take(300).orEmpty()
                        )
                    )
                }
                val file = withContext(Dispatchers.IO) {
                    val f = File(cacheDir, "scrape_${System.currentTimeMillis()}.xlsx")
                    ExcelCsvHelper.writeXlsx(rows, f, sheetName = "Scraped Data")
                    f
                }
                pendingXlsxFile = file
                binding.btnSaveXlsx.isEnabled = true
            } catch (e: Exception) {
                binding.btnSaveXlsx.isEnabled = false
                Toast.makeText(this@WebScrapingActivity, "Could not prepare Excel export: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnScrape.isEnabled = !busy
        binding.btnSave.isEnabled = !busy
        binding.btnSaveXlsx.isEnabled = !busy && pendingXlsxFile != null
    }

    private fun saveResults() {
        val text = binding.etText.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "Nothing to save yet", Toast.LENGTH_SHORT).show()
            return
        }
        saveTextAs.launch("scrape_${System.currentTimeMillis()}.txt")
    }

    private fun saveXlsx() {
        val file = pendingXlsxFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Scrape a page first", Toast.LENGTH_SHORT).show()
            return
        }
        saveXlsxAs.launch(file.name)
    }

    private fun writeResultsTo(uri: Uri) {
        val result = lastResult
        val text = binding.etText.text.toString()
        val content = buildString {
            append("URL: ${result?.url ?: binding.etUrl.text}\n")
            append("Title: ${result?.title ?: ""}\n\n")
            append(text)
            if (result != null) {
                append("\n\n--- LINKS ---\n")
                result.links.forEach { append("$it\n") }
            }
        }
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray())
            } ?: throw IllegalStateException("Could not open destination for writing")
            Toast.makeText(this, "Saved", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyPendingXlsxTo(uri: Uri) {
        val file = pendingXlsxFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Nothing to save yet", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { input -> input.copyTo(out) }
                    } ?: throw IllegalStateException("Could not open destination for writing")
                }
                Toast.makeText(this@WebScrapingActivity, "Saved", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@WebScrapingActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
