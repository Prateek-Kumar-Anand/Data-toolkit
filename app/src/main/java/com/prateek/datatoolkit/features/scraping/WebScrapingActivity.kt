package com.prateek.datatoolkit.features.scraping

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.prateek.datatoolkit.R
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

    /** Builds the manual-selector override from the "Custom Scraping" card, or null if the
     *  container field is blank (i.e. the user wants plain auto-detection). */
    private fun readManualSelectors(): ManualSelectors? {
        val container = binding.etContainerSelector.text.toString().trim()
        if (container.isBlank()) return null
        return ManualSelectors(
            containerSelector = container,
            titleSelector = binding.etTitleSelector.text.toString().trim(),
            priceSelector = binding.etPriceSelector.text.toString().trim(),
            descriptionSelector = binding.etDescriptionSelector.text.toString().trim(),
            imageSelector = binding.etImageSelector.text.toString().trim(),
            linkSelector = binding.etLinkSelector.text.toString().trim()
        )
    }

    private fun runScrape() {
        val urls = parseUrls(binding.etUrl.text.toString())
        if (urls.isEmpty()) {
            Toast.makeText(this, "Enter at least one URL first", Toast.LENGTH_SHORT).show()
            return
        }

        val manualSelectors = readManualSelectors()
        val customUserAgent = binding.etUserAgent.text.toString().trim()

        setBusy(true)
        binding.progressBar.max = urls.size
        binding.progressBar.progress = 0
        binding.tvStatus.text = "Starting..."
        binding.tvItemCount.text = ""
        renderItemsPreview(emptyList())

        lifecycleScope.launch {
            val allItems = mutableListOf<ScrapedItem>()
            val allSources = mutableListOf<String>()
            var pagesOk = 0
            var pagesFailed = 0
            var cacheHits = 0
            var lastGoodResult: ScrapeResult? = null
            val overallStart = System.currentTimeMillis()

            for ((index, url) in urls.withIndex()) {
                val pct = ((index) * 100) / urls.size
                binding.tvStatus.text = "Scraping page ${index + 1} of ${urls.size} ($pct%)"
                binding.tvItemCount.text = "${allItems.size} item(s) found so far"
                val start = System.currentTimeMillis()

                // Smart caching: skip the network entirely if we scraped this exact URL before -
                // but only when the user isn't using a one-off custom selector for this run,
                // since a cached result was extracted with whatever selectors applied back then.
                val cached = if (manualSelectors == null) cache.findCached("SCRAPING", url) else null
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
                    val result = Scraper.scrape(
                        url,
                        userAgent = customUserAgent.ifBlank { Scraper.DEFAULT_USER_AGENT },
                        manualSelectors = manualSelectors
                    )
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
                    pagesFailed++
                    if (urls.size == 1) {
                        binding.tvStatus.text = "Scrape failed: ${e.message}"
                    }
                    cache.record(
                        feature = "SCRAPING",
                        inputText = url,
                        inputLabel = url,
                        outputPreview = "Failed: ${e.message}",
                        outputPath = null,
                        qualityScore = 0,
                        status = "FAILED",
                        durationMs = System.currentTimeMillis() - start
                    )
                }

                binding.progressBar.progress = index + 1
                binding.tvItemCount.text = "${allItems.size} item(s) found so far"
            }

            lastItems = allItems
            lastItemSources = allSources

            val totalMs = System.currentTimeMillis() - overallStart
            binding.tvStatus.text = if (pagesOk == 0 && cacheHits == 0) {
                "Failed to scrape ${urls.size} page(s) after retries"
            } else {
                val cacheNote = if (cacheHits > 0) "  •  $cacheHits from cache" else ""
                val failNote = if (pagesFailed > 0) "  •  $pagesFailed failed" else ""
                "Done: ${pagesOk + cacheHits}/${urls.size} page(s) ready in ${totalMs}ms$cacheNote$failNote  •  " +
                    "Quality: ${lastGoodResult?.let { QualityScorer.scoreText(it.text) } ?: 0}/100"
            }
            binding.tvItemCount.text = if (allItems.isEmpty()) "" else "${allItems.size} item(s) found total"

            renderItemsPreview(allItems)
            buildAutoXlsx(allItems, allSources)
            setBusy(false)
        }
    }

    // ---- Structured preview of scraped items, shown before export ----------------------------

    private fun renderItemsPreview(items: List<ScrapedItem>) {
        binding.itemsContainer.removeAllViews()
        binding.tvItemsEmptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        for ((index, item) in items.withIndex()) {
            binding.itemsContainer.addView(buildItemPreviewRow(item, index))
        }
    }

    private fun buildItemPreviewRow(item: ScrapedItem, index: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = ContextCompat.getDrawable(this@WebScrapingActivity, R.drawable.bg_input_field)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        headerRow.addView(TextView(this).apply {
            text = "${index + 1}. ${item.name?.ifBlank { null } ?: "(no name detected)"}"
            setTextColor(colorOf(R.color.text_primary))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (!item.image.isNullOrBlank()) {
            headerRow.addView(TextView(this).apply {
                text = "🖼"
                textSize = 13f
                setPadding(dp(6), 0, 0, 0)
            })
        }
        row.addView(headerRow)

        val summary = listOfNotNull(
            item.price?.ifBlank { null }?.let { "💰 $it" },
            item.rating?.ifBlank { null }?.let { "★ $it" },
            item.category?.ifBlank { null }
        ).joinToString("   ")
        if (summary.isNotBlank()) {
            row.addView(TextView(this).apply {
                text = summary
                setTextColor(colorOf(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(3), 0, 0)
            })
        }

        if (!item.description.isNullOrBlank()) {
            row.addView(TextView(this).apply {
                text = item.description
                setTextColor(colorOf(R.color.text_secondary))
                textSize = 11.5f
                maxLines = 2
                setPadding(0, dp(3), 0, 0)
            })
        }

        if (!item.link.isNullOrBlank()) {
            row.addView(TextView(this).apply {
                text = item.link
                setTextColor(colorOf(R.color.primary))
                textSize = 10.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(0, dp(3), 0, 0)
            })
        }

        return row
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
                    if (items.isNotEmpty()) {
                        ExcelCsvHelper.writeXlsxWithImages(
                            rows, f, sheetName = "Scraped Data", imageColumn = ItemExtractor.IMAGE_COLUMN_INDEX
                        )
                    } else {
                        ExcelCsvHelper.writeXlsx(rows, f, sheetName = "Scraped Data")
                    }
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun colorOf(resId: Int) = ContextCompat.getColor(this, resId)
}
