package com.prateek.datatoolkit.features.scraping

import com.prateek.datatoolkit.core.network.RetryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.UnsupportedMimeTypeException
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class ScrapeResult(
    val url: String,
    val title: String,
    val text: String,
    val links: List<String>,
    val tables: List<List<List<String>>>, // list of tables, each a list of rows, each a list of cells
    val attempts: Int,
    // Auto-detected structured records on the page (product cards, articles, listings...).
    // Empty when the page doesn't look like a listing - callers should fall back to text/links/tables.
    val items: List<ScrapedItem> = emptyList()
)

/**
 * Web Scraping: fetches a page (off the main thread) with automatic retry on
 * transient failures via RetryPolicy, then extracts the pieces most useful
 * downstream - visible text, links, tables, and (via [ItemExtractor]) any
 * auto-detected product/article cards.
 */
object Scraper {

    /** A realistic, current desktop-Chrome UA. Sites that block obviously-non-browser User-
     *  Agents (many do) will often still serve a normal response to this, whereas the old
     *  literal "DataToolkit Android App" string identified every request as a bot and was
     *  frequently blocked outright or served a stripped-down page with none of the real content. */
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    suspend fun scrape(
        url: String,
        timeoutMs: Int = 10000,
        userAgent: String = DEFAULT_USER_AGENT,
        maxAttempts: Int = 3,
        manualSelectors: ManualSelectors? = null
    ): ScrapeResult = withContext(Dispatchers.IO) {
        val result = RetryPolicy.withRetry(
            maxAttempts = maxAttempts,
            shouldRetry = ::isRetryable
        ) {
            Jsoup.connect(url)
                .userAgent(userAgent.ifBlank { DEFAULT_USER_AGENT })
                .timeout(timeoutMs)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .followRedirects(true)
                .get()
        }

        val doc: Document = result.value ?: throw classifyError(result.lastError, url)

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
            attempts = result.attempts,
            items = ItemExtractor.extract(doc, url, manualSelectors)
        )
    }

    /**
     * Only retry failures a second attempt could plausibly fix - a slow/flaky connection, a DNS
     * hiccup, a "please slow down" 429, or the server's own 5xx error. A malformed URL, a 404,
     * or a 403 will fail exactly the same way every time, so retrying those would just make the
     * user wait 3x as long before reporting a failure the very first attempt already knew about.
     */
    private fun isRetryable(t: Throwable): Boolean = when (t) {
        is IllegalArgumentException -> false // malformed URL - jsoup won't parse it differently next time
        is UnsupportedMimeTypeException -> false // e.g. linked straight to a PDF/image, not HTML
        is HttpStatusException -> t.statusCode >= 500 || t.statusCode == 429
        is UnknownHostException -> false // DNS won't resolve any differently on retry
        is SocketTimeoutException -> true
        is IOException -> true
        else -> true
    }

    /** Turns jsoup/network exceptions into a short, specific message instead of a raw stack
     *  trace fragment - this is what ends up in the UI's status line and in scrape history. */
    private fun classifyError(t: Throwable?, url: String): Exception = when (t) {
        is HttpStatusException -> IOException("Server returned HTTP ${t.statusCode} for $url", t)
        is UnsupportedMimeTypeException -> IOException("$url isn't an HTML page (got ${t.mimeType})", t)
        is SocketTimeoutException -> IOException("Timed out waiting for $url to respond", t)
        is UnknownHostException -> IOException("Could not resolve host for $url - check the URL or your connection", t)
        is IllegalArgumentException -> IOException("\"$url\" doesn't look like a valid URL", t)
        null -> IOException("Scrape failed for $url")
        else -> IOException(t.message ?: "Scrape failed for $url", t)
    }
}
