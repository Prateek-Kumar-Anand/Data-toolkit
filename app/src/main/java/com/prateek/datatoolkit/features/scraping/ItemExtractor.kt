package com.prateek.datatoolkit.features.scraping

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** One auto-detected record on a scraped page (e.g. one product/article/listing card). */
data class ScrapedItem(
    val name: String? = null,
    val description: String? = null,
    val price: String? = null,
    val rating: String? = null,
    val link: String? = null,
    val image: String? = null,
    val category: String? = null
) {
    /** True if at least one field beyond name was actually found - filters out empty noise. */
    fun isMeaningful(): Boolean =
        !name.isNullOrBlank() || !description.isNullOrBlank() || !price.isNullOrBlank() ||
            !rating.isNullOrBlank() || !link.isNullOrBlank() || !image.isNullOrBlank() || !category.isNullOrBlank()
}

/**
 * Field/Tag Auto-Detection: given a parsed HTML page, finds repeated
 * "item" structures (product cards, article previews, listing rows...) and
 * pulls out common commerce/content fields into named columns, without any
 * site-specific configuration.
 *
 * Three strategies are tried in order, each more heuristic than the last:
 *  1. schema.org microdata (itemscope/itemprop) - most reliable when present.
 *  2. Repeated-sibling heuristic - finds the largest group of same-tag,
 *     same-class elements on the page and treats each as one item.
 *  3. Single-item fallback from page-level <meta> tags (OpenGraph etc.),
 *     so a plain article/blog page still yields one usable row instead of nothing.
 *
 * This is intentionally best-effort: real-world markup is too varied for
 * perfect field detection, but this covers the common cases (product
 * listings, article grids, search results) reasonably well.
 */
object ItemExtractor {

    private const val MIN_REPEATED_ELEMENTS = 3

    /** Zero-based column index of "Image" in [toRows]'s header - used by the xlsx image-embedding export. */
    const val IMAGE_COLUMN_INDEX = 5

    fun extract(doc: Document, baseUri: String): List<ScrapedItem> {
        val microdata = extractMicrodata(doc)
        if (microdata.isNotEmpty()) return microdata

        val cards = extractRepeatedCards(doc)
        if (cards.isNotEmpty()) return cards

        return listOfNotNull(extractPageMeta(doc))
    }

    // --- Strategy 1: schema.org microdata -----------------------------------------------------

    private fun extractMicrodata(doc: Document): List<ScrapedItem> {
        val scopes = doc.select("[itemscope]")
        if (scopes.size < 1) return emptyList()

        val items = scopes.mapNotNull { scope ->
            fun prop(name: String): String? =
                scope.select("[itemprop=$name]").firstOrNull()?.let { el ->
                    when {
                        el.hasAttr("content") -> el.attr("content")
                        el.tagName() == "img" -> el.absUrl("src").ifBlank { el.attr("src") }
                        el.tagName() == "a" -> el.absUrl("href").ifBlank { el.attr("href") }
                        el.hasAttr("datetime") -> el.attr("datetime")
                        else -> el.text()
                    }.trim().ifBlank { null }
                }

            val item = ScrapedItem(
                name = prop("name") ?: prop("headline") ?: prop("title"),
                description = prop("description"),
                price = prop("price") ?: prop("lowPrice"),
                rating = prop("ratingValue") ?: prop("ratingScore"),
                link = prop("url") ?: scope.select("a[href]").firstOrNull()?.absUrl("href"),
                image = prop("image"),
                category = prop("category")
            )
            item.takeIf { it.isMeaningful() }
        }
        // Only trust microdata if it actually produced multiple usable, distinct items -
        // a single stray itemscope somewhere in the page (e.g. site nav) isn't a listing.
        return if (items.size >= MIN_REPEATED_ELEMENTS || (items.size in 1..2 && items.all { it.name != null })) items else emptyList()
    }

    // --- Strategy 2: repeated same-tag/same-class siblings ------------------------------------

    private fun extractRepeatedCards(doc: Document): List<ScrapedItem> {
        val candidates = doc.select("article, li, div, section").toList()
        // jsoup's Elements also declares a member filter(NodeFilter) overload, which Kotlin
        // would otherwise prefer over the stdlib Iterable.filter extension and try (and fail)
        // to SAM-convert a one-arg lambda into a two-arg NodeFilter - hence the .toList() above
        // before any .filter{} call in this function.

        // Group by (parent, tag, one individual class token) rather than the whole raw class
        // attribute string. Real card grids very often give each card an extra one-off modifier
        // class ("product-card featured" vs "product-card sale" vs plain "product-card"), so
        // matching the full concatenated className() string required every card's classes to be
        // byte-for-byte identical - which almost never happens on real sites - and silently fell
        // back to a single whole-page item, losing every per-card name/description in the process.
        val groups = LinkedHashMap<Triple<Element?, String, String>, MutableList<Element>>()
        for (el in candidates) {
            val parent = el.parent()
            for (token in el.classNames()) {
                if (token.isBlank()) continue
                groups.getOrPut(Triple(parent, el.tagName(), token)) { mutableListOf() }.add(el)
            }
        }

        val sizeable = groups.values.filter { it.size >= MIN_REPEATED_ELEMENTS }
        if (sizeable.isEmpty()) return emptyList()

        // Prefer the group whose members actually look like content cards (a link + some text),
        // not just the single largest group (which is often layout wrapper divs).
        val best = sizeable
            .filter { group -> group.count { hasLinkOrHeading(it) } >= (group.size / 2).coerceAtLeast(1) }
            .maxByOrNull { it.size }
            ?: sizeable.maxByOrNull { it.size }
            ?: return emptyList()

        val items = best.map { el -> extractFromCard(el) }.filter { it.isMeaningful() }
        return if (items.size >= MIN_REPEATED_ELEMENTS) items else emptyList()
    }

    private fun hasLinkOrHeading(el: Element): Boolean =
        el.select("a[href]").isNotEmpty() || el.select("h1, h2, h3, h4").isNotEmpty()

    private fun extractFromCard(el: Element): ScrapedItem {
        val name = el.select("h1, h2, h3, h4, h5, [class*=title], [class*=name], [class*=heading]").firstOrNull()?.text()
            ?: el.select("a[href]").firstOrNull()?.let { it.text().ifBlank { it.attr("title") } }
            ?: el.select("img[alt]").firstOrNull()?.attr("alt")

        val description = el.select("p, [class*=desc], [class*=summary], [class*=subtitle], [class*=excerpt]")
            .firstOrNull()?.text()

        val price = el.select("[class*=price], [itemprop=price]").firstOrNull()?.text()
            ?: PRICE_REGEX.find(el.text())?.value

        val rating = el.select("[class*=rating], [class*=stars], [aria-label*=rating]").firstOrNull()
            ?.let { it.attr("aria-label").ifBlank { it.text() } }
            ?: RATING_REGEX.find(el.text())?.value

        val link = el.select("a[href]").firstOrNull()?.absUrl("href")

        val image = el.select("img").firstOrNull()?.let {
            it.absUrl("src").ifBlank { it.attr("data-src") }
        }

        val category = el.select("[class*=category], [class*=tag], [class*=badge], [class*=breadcrumb]")
            .firstOrNull()?.text()

        return ScrapedItem(
            name = name?.trim()?.ifBlank { null },
            description = description?.trim()?.ifBlank { null },
            price = price?.trim()?.ifBlank { null },
            rating = rating?.trim()?.ifBlank { null },
            link = link?.ifBlank { null },
            image = image?.ifBlank { null },
            category = category?.trim()?.ifBlank { null }
        )
    }

    // --- Strategy 3: whole-page fallback via <meta> tags --------------------------------------

    private fun extractPageMeta(doc: Document): ScrapedItem? {
        fun meta(name: String): String? =
            doc.select("meta[property=$name], meta[name=$name]").firstOrNull()?.attr("content")?.trim()?.ifBlank { null }

        val item = ScrapedItem(
            name = meta("og:title") ?: doc.title().ifBlank { null },
            description = meta("og:description") ?: meta("description"),
            price = meta("product:price:amount") ?: meta("og:price:amount"),
            rating = null,
            link = meta("og:url"),
            image = meta("og:image"),
            category = meta("article:section") ?: meta("og:type")
        )
        return item.takeIf { it.isMeaningful() }
    }

    private val PRICE_REGEX = Regex("(₹|\\$|€|£)\\s?[0-9][0-9,.]*")
    private val RATING_REGEX = Regex("[0-5](\\.[0-9])?\\s*(out of\\s*5|stars|★)", RegexOption.IGNORE_CASE)

    /** Converts a list of items (optionally from several pages) into export rows with a header. */
    fun toRows(items: List<ScrapedItem>, includeSourceColumn: Boolean = false, sources: List<String> = emptyList()): List<List<String>> {
        val header = mutableListOf("Name", "Description", "Price", "Rating", "Link", "Image", "Category")
        if (includeSourceColumn) header.add("Source URL")

        val rows = items.mapIndexed { index, item ->
            val row = mutableListOf(
                item.name.orEmpty(),
                item.description.orEmpty(),
                item.price.orEmpty(),
                item.rating.orEmpty(),
                item.link.orEmpty(),
                item.image.orEmpty(),
                item.category.orEmpty()
            )
            if (includeSourceColumn) row.add(sources.getOrElse(index) { "" })
            row
        }
        return listOf(header) + rows
    }
}
