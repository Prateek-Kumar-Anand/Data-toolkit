package com.prateek.datatoolkit.features.scraping

import com.prateek.datatoolkit.core.network.RetryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class ScrapeResult(
    val url: String,
    val title: String,
    val text: String,
    val links: List<String>,
    val tables: List<List<List<String>>>, // list of tables, each a list of rows, each a list of cells
    val attempts: Int
)

/**
 * Web Scraping: fetches a page (off the main thread) with automatic retry on
 * transient failures via RetryPolicy, then extracts the pieces most useful
 * downstream - visible text, links, and any HTML tables.
 */
object Scraper {

    suspend fun scrape(url: String, timeoutMs: Int = 10000): ScrapeResult = withContext(Dispatchers.IO) {
        val result = RetryPolicy.withRetry(
            maxAttempts = 3,
            shouldRetry = { it !is IllegalArgumentException } // don't retry a malformed URL
        ) {
            Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (DataToolkit Android App)")
                .timeout(timeoutMs)
                .get()
        }

        val doc: Document = result.value ?: throw result.lastError ?: RuntimeException("Scrape failed")

        val links = doc.select("a[href]").map { it.absUrl("href") }.filter { it.isNotBlank() }.distinct()

        val tables = doc.select("table").map { table ->
            table.select("tr").map { tr ->
                tr.select("th, td").map { it.text() }
            }
        }

        ScrapeResult(
            url = url,
            title = doc.title(),
            text = doc.body()?.text().orEmpty(),
            links = links,
            tables = tables,
            attempts = result.attempts
        )
    }
}
