package com.prateek.datatoolkit.features.scraping

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** One auto-detected record on a scraped page (e.g. one product/article/listing card).
 *
 *  The seven named fields cover what most product/article/listing cards have in common,
 *  but plenty of sites print data that doesn't fit any of them - a job listing's company/
 *  location/salary, a real-estate card's bedrooms/area, a recipe's prep time. [extra] holds
 *  whatever [ItemExtractor] additionally detected on a *given* card under its own label
 *  (from a dt/dd spec list, a schema.org property, a JSON-LD field, or a plain
 *  "Label: value" line in the markup) instead of that data being silently dropped just
 *  because it doesn't match one of the seven fixed slots. */
data class ScrapedItem(
    val name: String? = null,
    val description: String? = null,
    val price: String? = null,
    val rating: String? = null,
    val link: String? = null,
    val image: String? = null,
    val category: String? = null,
    val extra: LinkedHashMap<String, String> = LinkedHashMap()
) {
    /** True if at least one field - core or site-specific extra - was actually found. */
    fun isMeaningful(): Boolean =
        !name.isNullOrBlank() || !description.isNullOrBlank() || !price.isNullOrBlank() ||
            !rating.isNullOrBlank() || !link.isNullOrBlank() || !image.isNullOrBlank() || !category.isNullOrBlank() ||
            extra.values.any { it.isNotBlank() }
}

/**
 * User-supplied CSS selectors for "custom scraping" mode: [containerSelector] picks out each
 * item card, and the rest are optional per-field selectors *relative to that container*. Any
 * field left blank still falls back to the same heuristics auto-detection uses, so a user can
 * override just the one field a site's markup confuses (e.g. only the price) without having to
 * hand-write selectors for everything else.
 */
data class ManualSelectors(
    val containerSelector: String,
    val titleSelector: String = "",
    val priceSelector: String = "",
    val descriptionSelector: String = "",
    val imageSelector: String = "",
    val linkSelector: String = ""
)

/**
 * Field/Tag Auto-Detection: given a parsed HTML page, finds repeated
 * "item" structures (product cards, article previews, listing rows...) and
 * pulls out common commerce/content fields into named columns, without any
 * site-specific configuration.
 *
 * Strategies are tried in order, each more heuristic than the last:
 *  0. Manual CSS selectors (only when the caller supplies one) - an explicit user override.
 *  1. JSON-LD (schema.org, `<script type="application/ld+json">`) - very common on modern
 *     e-commerce/publishing sites, and more complete than inline microdata when present.
 *  2. schema.org microdata (itemscope/itemprop) - reliable when present.
 *  3. Repeated-sibling heuristic - finds the largest group of same-tag,
 *     same-class elements on the page and treats each as one item.
 *  4. Single-item fallback from page-level <meta> tags (OpenGraph, Twitter Card...),
 *     so a plain article/blog page still yields one usable row instead of nothing.
 *
 * This is intentionally best-effort and entirely rule-based (plain CSS-selector and JSON
 * parsing, no AI/ML/LLM and no external API calls beyond the one page fetch the caller already
 * made): real-world markup is too varied for perfect field detection, but this covers the
 * common cases (product listings, article grids, search results) reasonably well.
 */
object ItemExtractor {

    private const val MIN_REPEATED_ELEMENTS = 3

    /** Zero-based column index of "Image" in [toRows]'s header - used by the xlsx image-embedding export. */
    const val IMAGE_COLUMN_INDEX = 5

    fun extract(doc: Document, baseUri: String, manual: ManualSelectors? = null): List<ScrapedItem> {
        if (manual != null && manual.containerSelector.isNotBlank()) {
            val manualItems = extractManual(doc, manual)
            // A custom selector that matched nothing is most likely a typo/wrong site layout -
            // fall through to auto-detection rather than showing the user an empty result.
            if (manualItems.isNotEmpty()) return manualItems
        }

        val jsonLd = extractJsonLd(doc)
        if (jsonLd.isNotEmpty()) return jsonLd

        val microdata = extractMicrodata(doc)
        if (microdata.isNotEmpty()) return microdata

        val cards = extractRepeatedCards(doc)
        if (cards.isNotEmpty()) return cards

        return listOfNotNull(extractPageMeta(doc))
    }

    // --- Strategy 0: manual, user-supplied CSS selectors --------------------------------------

    private fun extractManual(doc: Document, manual: ManualSelectors): List<ScrapedItem> {
        val containers = try {
            doc.select(manual.containerSelector)
        } catch (e: Exception) {
            // Invalid selector syntax - treat exactly like "no matches" rather than crashing.
            return emptyList()
        }
        if (containers.isEmpty()) return emptyList()

        fun sub(el: Element, selector: String): Element? {
            if (selector.isBlank()) return null
            return try {
                el.selectFirst(selector)
            } catch (e: Exception) {
                null
            }
        }

        return containers.map { el ->
            val title = sub(el, manual.titleSelector)?.text()?.trim()?.ifBlank { null } ?: titleOf(el)
            val price = sub(el, manual.priceSelector)?.text()?.trim()?.ifBlank { null } ?: priceOf(el)
            val description = sub(el, manual.descriptionSelector)?.text()?.trim()?.ifBlank { null } ?: descriptionOf(el)
            val imageScope = sub(el, manual.imageSelector)
            val image = (imageScope?.let { resolveImageUrl(it) }) ?: resolveImageUrl(el)
            val linkTarget = sub(el, manual.linkSelector)
            val link = when {
                linkTarget != null && linkTarget.tagName().equals("a", ignoreCase = true) -> linkTarget.absUrl("href")
                linkTarget != null -> linkTarget.selectFirst("a[href]")?.absUrl("href")
                else -> linkOf(el)
            }?.ifBlank { null }

            ScrapedItem(
                name = title,
                description = description,
                price = price,
                rating = ratingOf(el),
                link = link,
                image = image,
                category = categoryOf(el),
                extra = extraFieldsFromCard(el)
            )
        }.filter { it.isMeaningful() }
    }

    // --- Strategy 1: JSON-LD (schema.org) --------------------------------------------------

    private val JSONLD_ITEM_TYPES = setOf(
        "product", "article", "newsarticle", "blogposting", "recipe", "event", "book", "movie",
        "jobposting", "restaurant", "localbusiness", "softwareapplication", "offer"
    )

    private fun extractJsonLd(doc: Document): List<ScrapedItem> {
        val scripts = doc.select("script[type=application/ld+json]")
        if (scripts.isEmpty()) return emptyList()

        val found = mutableListOf<ScrapedItem>()
        for (script in scripts) {
            val raw = script.data().ifBlank { script.html() }.trim()
            if (raw.isBlank()) continue
            val roots: List<Any?> = try {
                if (raw.startsWith("[")) {
                    val arr = JSONArray(raw)
                    (0 until arr.length()).map { arr.opt(it) }
                } else {
                    listOf(JSONObject(raw))
                }
            } catch (e: Exception) {
                // Malformed/partial JSON-LD (surprisingly common in the wild) - skip this block,
                // other scripts or extraction strategies may still succeed.
                continue
            }
            for (root in roots) collectJsonLdEntities(root, doc.baseUri(), found)
        }

        // Some sites repeat the same product/article JSON-LD in more than one block - collapse
        // obvious duplicates before deciding whether this strategy "worked".
        val deduped = found.distinctBy { Triple(it.name, it.link, it.price) }.filter { it.isMeaningful() }
        return deduped
    }

    private fun collectJsonLdEntities(node: Any?, baseUri: String, out: MutableList<ScrapedItem>) {
        when (node) {
            is JSONObject -> {
                val typeRaw = node.opt("@type")
                val types = when (typeRaw) {
                    is String -> listOf(typeRaw)
                    is JSONArray -> (0 until typeRaw.length()).mapNotNull { typeRaw.optString(it, null) }
                    else -> emptyList()
                }.map { it.lowercase() }

                if (types.any { it in JSONLD_ITEM_TYPES }) {
                    jsonLdToItem(node, baseUri)?.let { out.add(it) }
                }

                // Recurse into common nesting points - JSON-LD often wraps the real entities in
                // one of these rather than listing them at the top level.
                node.opt("@graph")?.let { collectJsonLdEntities(it, baseUri, out) }
                node.opt("itemListElement")?.let { collectJsonLdEntities(it, baseUri, out) }
                node.opt("mainEntity")?.let { collectJsonLdEntities(it, baseUri, out) }
                (node.opt("item") as? JSONObject)?.let { collectJsonLdEntities(it, baseUri, out) }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collectJsonLdEntities(node.opt(i), baseUri, out)
            }
            else -> {}
        }
    }

    private fun jsonLdToItem(obj: JSONObject, baseUri: String): ScrapedItem? {
        fun str(vararg keys: String): String? {
            for (k in keys) {
                when (val v = obj.opt(k)) {
                    is String -> if (v.isNotBlank()) return v.trim()
                    is Number -> return v.toString()
                    else -> {}
                }
            }
            return null
        }

        val offersNode = obj.opt("offers")
        val offer = when (offersNode) {
            is JSONObject -> offersNode
            is JSONArray -> if (offersNode.length() > 0) offersNode.optJSONObject(0) else null
            else -> null
        }
        val price = offer?.let { o ->
            val p = o.opt("price") ?: o.opt("lowPrice")
            val currency = o.optString("priceCurrency", "")
            val amount = when (p) {
                is String -> p.takeIf { it.isNotBlank() }
                is Number -> p.toString()
                else -> null
            }
            amount?.let { if (currency.isNotBlank()) "$currency $it" else it }
        } ?: str("price", "lowPrice")

        val rating = obj.optJSONObject("aggregateRating")?.opt("ratingValue")?.toString()
        val image = extractJsonLdImage(obj.opt("image"), baseUri)
        val link = str("url")?.let { resolveRelative(it, baseUri) }

        val item = ScrapedItem(
            name = str("name", "headline"),
            description = str("description"),
            price = price,
            rating = rating,
            link = link,
            image = image,
            category = str("category", "articleSection"),
            extra = jsonLdExtraFields(obj)
        )
        return item.takeIf { it.isMeaningful() }
    }

    /** Every other scalar (or name-bearing object/array) property on a JSON-LD entity that
     *  isn't one of the 7 fields already mapped above - brand, sku, author, datePublished,
     *  employmentType, isbn, whatever a particular schema.org type happens to carry - kept
     *  under its own detected name instead of being discarded. */
    private val JSONLD_CORE_KEYS = setOf(
        "@context", "@type", "@id", "name", "headline", "description", "price", "lowprice",
        "pricecurrency", "offers", "aggregaterating", "image", "url", "category", "articlesection"
    )

    private fun jsonLdExtraFields(obj: JSONObject): LinkedHashMap<String, String> {
        val extra = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            if (extra.size >= EXTRA_FIELD_CAP) break
            val key = keys.next()
            if (key.lowercase() in JSONLD_CORE_KEYS) continue
            val text = when (val value = obj.opt(key)) {
                is String -> value.takeIf { it.isNotBlank() }
                is Number, is Boolean -> value.toString()
                is JSONObject -> value.optString("name", "").takeIf { it.isNotBlank() }
                is JSONArray -> (0 until value.length()).mapNotNull { i ->
                    when (val v = value.opt(i)) {
                        is String -> v
                        is JSONObject -> v.optString("name", null)
                        else -> null
                    }
                }.filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }
                else -> null
            } ?: continue
            extra[titleCase(key)] = text
        }
        return extra
    }

    private fun extractJsonLdImage(node: Any?, baseUri: String): String? = when (node) {
        is String -> node.takeIf { it.isNotBlank() }?.let { resolveRelative(it, baseUri) }
        is JSONObject -> node.optString("url", "").takeIf { it.isNotBlank() }?.let { resolveRelative(it, baseUri) }
        is JSONArray -> if (node.length() > 0) extractJsonLdImage(node.opt(0), baseUri) else null
        else -> null
    }

    // --- Strategy 2: schema.org microdata -----------------------------------------------------

    private fun extractMicrodata(doc: Document): List<ScrapedItem> {
        val scopes = doc.select("[itemscope]")
        if (scopes.size < 1) return emptyList()

        val items = scopes.mapNotNull { scope ->
            fun prop(name: String): String? =
                scope.select("[itemprop=$name]").firstOrNull()?.let { el ->
                    when {
                        el.tagName() == "img" -> resolveImageUrl(el) ?: el.attr("content")
                        el.hasAttr("content") -> el.attr("content")
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
                category = prop("category"),
                extra = microdataExtraFields(scope)
            )
            item.takeIf { it.isMeaningful() }
        }
        // Only trust microdata if it actually produced multiple usable, distinct items -
        // a single stray itemscope somewhere in the page (e.g. site nav) isn't a listing.
        return if (items.size >= MIN_REPEATED_ELEMENTS || (items.size in 1..2 && items.all { it.name != null })) items else emptyList()
    }

    private val MICRODATA_CORE_PROPS = setOf(
        "name", "headline", "title", "description", "price", "lowprice", "ratingvalue",
        "ratingscore", "url", "image", "category"
    )

    /** Every itemprop on a microdata scope beyond the ones already mapped to a core field -
     *  e.g. brand, sku, availability, author - kept under its own detected name. */
    private fun microdataExtraFields(scope: Element): LinkedHashMap<String, String> {
        val extra = LinkedHashMap<String, String>()
        for (el in scope.select("[itemprop]")) {
            if (extra.size >= EXTRA_FIELD_CAP) break
            val propName = el.attr("itemprop").trim()
            if (propName.isBlank() || propName.lowercase() in MICRODATA_CORE_PROPS) continue
            val label = titleCase(propName)
            if (extra.containsKey(label)) continue
            val value = when {
                el.tagName() == "img" -> resolveImageUrl(el) ?: el.attr("content")
                el.hasAttr("content") -> el.attr("content")
                el.tagName() == "a" -> el.absUrl("href").ifBlank { el.attr("href") }
                el.hasAttr("datetime") -> el.attr("datetime")
                else -> el.text()
            }.trim()
            if (value.isNotBlank()) extra[label] = value
        }
        return extra
    }

    // --- Strategy 3: repeated same-tag/same-class siblings ------------------------------------

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

    private fun extractFromCard(el: Element): ScrapedItem = ScrapedItem(
        name = titleOf(el)?.trim()?.ifBlank { null },
        description = descriptionOf(el)?.trim()?.ifBlank { null },
        price = priceOf(el)?.trim()?.ifBlank { null },
        rating = ratingOf(el)?.trim()?.ifBlank { null },
        link = linkOf(el)?.ifBlank { null },
        image = resolveImageUrl(el)?.ifBlank { null },
        category = categoryOf(el)?.trim()?.ifBlank { null },
        extra = extraFieldsFromCard(el)
    )

    // Per-field heuristics, factored out so both the repeated-cards strategy and manual-selector
    // mode's "leave this field blank to auto-detect it" behaviour share exactly one implementation.

    private fun titleOf(el: Element): String? =
        el.select("h1, h2, h3, h4, h5, [class*=title], [class*=name], [class*=heading]").firstOrNull()?.text()
            ?: el.select("a[href]").firstOrNull()?.let { it.text().ifBlank { it.attr("title") } }
            ?: el.select("img[alt]").firstOrNull()?.attr("alt")

    private fun descriptionOf(el: Element): String? =
        el.select("p, [class*=desc], [class*=summary], [class*=subtitle], [class*=excerpt]").firstOrNull()?.text()

    private fun priceOf(el: Element): String? =
        el.select("[class*=price], [itemprop=price]").firstOrNull()?.text()
            ?: PRICE_REGEX.find(el.text())?.value

    private fun ratingOf(el: Element): String? =
        el.select("[class*=rating], [class*=stars], [aria-label*=rating]").firstOrNull()
            ?.let { it.attr("aria-label").ifBlank { it.text() } }
            ?: RATING_REGEX.find(el.text())?.value

    private fun linkOf(el: Element): String? = el.select("a[href]").firstOrNull()?.absUrl("href")

    private fun categoryOf(el: Element): String? =
        el.select("[class*=category], [class*=tag], [class*=badge], [class*=breadcrumb]").firstOrNull()?.text()

    // --- Extra-field detection: whatever a card has beyond the 7 core fields --------------------

    /** Cap on how many extra fields one card contributes - keeps a noisy page from exploding
     *  into dozens of spurious Excel columns. */
    private const val EXTRA_FIELD_CAP = 8

    private val CORE_LABEL_WORDS = setOf(
        "name", "title", "heading", "description", "desc", "summary", "subtitle", "excerpt",
        "price", "rating", "stars", "link", "href", "image", "img", "category", "tag", "badge", "breadcrumb"
    )

    /** class-token -> canonical label. Covers the common "spec sheet" attributes that show up
     *  outside plain commerce (real estate, job listings, recipes, directories...). */
    private val ATTRIBUTE_CLASS_HINTS = listOf(
        "brand" to "Brand", "sku" to "SKU", "location" to "Location", "address" to "Address",
        "author" to "Author", "publisher" to "Publisher", "duration" to "Duration",
        "availability" to "Availability", "stock" to "Stock", "bedroom" to "Bedrooms",
        "bathroom" to "Bathrooms", "area" to "Area", "salary" to "Salary",
        "employment" to "Employment Type", "isbn" to "ISBN", "weight" to "Weight",
        "color" to "Color", "colour" to "Color", "size" to "Size", "material" to "Material",
        "warranty" to "Warranty", "condition" to "Condition", "seller" to "Seller",
        "shipping" to "Shipping", "posted" to "Posted"
    )

    /** Safe generic fallback: a short own-text child written as "Label: value" - colon
     *  required so it doesn't collide with a plain description sentence. */
    private val genericCardLabelPattern = Regex("""^\s*([A-Za-z][A-Za-z ]{1,24}?)\s*[:]\s*(.+\S)\s*$""")

    /**
     * Whatever [el] (one item card) has beyond the 7 core fields, detected in three passes:
     * definition-list dt/dd pairs (a common "spec sheet" pattern), common attribute-ish class
     * names not tied to any one site, then a generic "Label: value" scan of the card's direct
     * children for anything the first two passes missed. One module, no site-specific config.
     */
    private fun extraFieldsFromCard(el: Element): LinkedHashMap<String, String> {
        val extra = LinkedHashMap<String, String>()

        for (dt in el.select("dt")) {
            if (extra.size >= EXTRA_FIELD_CAP) return extra
            val dd = dt.nextElementSibling()
            if (dd == null || !dd.tagName().equals("dd", ignoreCase = true)) continue
            val label = titleCase(dt.text().trim())
            val value = dd.text().trim()
            if (label.isBlank() || value.isBlank() || extra.containsKey(label)) continue
            extra[label] = value
        }

        for ((hint, label) in ATTRIBUTE_CLASS_HINTS) {
            if (extra.size >= EXTRA_FIELD_CAP) return extra
            if (extra.containsKey(label)) continue
            val value = el.select("[class*=$hint]").firstOrNull()?.text()?.trim()
            if (!value.isNullOrBlank()) extra[label] = value
        }

        for (child in el.children()) {
            if (extra.size >= EXTRA_FIELD_CAP) break
            val match = genericCardLabelPattern.find(child.ownText().trim()) ?: continue
            val label = titleCase(match.groupValues[1].trim())
            val value = match.groupValues[2].trim()
            if (label.isBlank() || value.isBlank() || label.lowercase() in CORE_LABEL_WORDS || extra.containsKey(label)) continue
            extra[label] = value
        }

        return extra
    }

    private fun titleCase(label: String): String =
        label.lowercase().split(Regex("""[\s_-]+""")).filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    // --- Image resolution: handles the lazy-loading patterns a plain img.attr("src") misses ---

    private val LAZY_IMG_ATTRS = listOf("data-src", "data-lazy-src", "data-lazy", "data-original", "data-echo")
    private val BG_IMAGE_REGEX = Regex("""background-image\s*:\s*url\(([^)]+)\)""", RegexOption.IGNORE_CASE)

    /**
     * Finds the best image URL within [scope] (an item card, or the image sub-selector's target
     * in manual mode). Checks, in order: known lazy-load data-* attributes (many sites put a
     * tiny placeholder in `src` and the real image in one of these), `srcset` (first candidate),
     * a plain `src` (skipped if it's a base64 `data:` placeholder rather than a real URL),
     * `<picture><source srcset>`, and finally an inline CSS `background-image`. Returns null
     * only if none of these are present - not just "if `src` is empty" like a naive check would.
     */
    private fun resolveImageUrl(scope: Element): String? {
        val img = if (scope.tagName().equals("img", ignoreCase = true)) scope else scope.selectFirst("img")
        if (img != null) {
            for (attr in LAZY_IMG_ATTRS) {
                if (img.attr(attr).isNotBlank()) {
                    val abs = img.absUrl(attr)
                    if (abs.isNotBlank()) return abs
                }
            }
            if (img.attr("srcset").isNotBlank()) {
                firstSrcsetUrl(img.attr("srcset"), img.baseUri())?.let { return it }
            }
            val src = img.attr("src")
            if (src.isNotBlank() && !src.startsWith("data:", ignoreCase = true)) {
                val abs = img.absUrl("src")
                if (abs.isNotBlank()) return abs
            }
        }

        val source = (if (scope.tagName().equals("picture", ignoreCase = true)) scope else scope.selectFirst("picture"))
            ?.selectFirst("source[srcset]")
        if (source != null && source.attr("srcset").isNotBlank()) {
            firstSrcsetUrl(source.attr("srcset"), source.baseUri())?.let { return it }
        }

        val bgHolder = if (scope.attr("style").contains("background-image")) scope else scope.selectFirst("[style*=background-image]")
        bgHolder?.let { holder ->
            BG_IMAGE_REGEX.find(holder.attr("style")).let { m ->
                val raw = m?.groupValues?.getOrNull(1)?.trim('\'', '"', ' ')
                if (!raw.isNullOrBlank()) return resolveRelative(raw, holder.baseUri())
            }
        }
        return null
    }

    private fun firstSrcsetUrl(srcset: String, baseUri: String): String? {
        val token = srcset.split(",").firstOrNull()?.trim()?.split(Regex("""\s+"""))?.firstOrNull()
        return token?.takeIf { it.isNotBlank() }?.let { resolveRelative(it, baseUri) }
    }

    private fun resolveRelative(url: String, baseUri: String): String = try {
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) url
        else java.net.URL(java.net.URL(baseUri), url).toString()
    } catch (e: Exception) {
        url
    }

    // --- Strategy 4: whole-page fallback via <meta> tags (OpenGraph, Twitter Card, generic) ---

    private fun extractPageMeta(doc: Document): ScrapedItem? {
        fun meta(vararg names: String): String? {
            for (n in names) {
                val v = doc.select("meta[property=$n], meta[name=$n]").firstOrNull()?.attr("content")?.trim()
                if (!v.isNullOrBlank()) return v
            }
            return null
        }

        val item = ScrapedItem(
            name = meta("og:title", "twitter:title") ?: doc.title().ifBlank { null },
            description = meta("og:description", "twitter:description") ?: meta("description"),
            price = meta("product:price:amount", "og:price:amount"),
            rating = null,
            link = meta("og:url"),
            image = meta("og:image", "twitter:image", "twitter:image:src")?.let { resolveRelative(it, doc.baseUri()) },
            category = meta("article:section", "og:type"),
            extra = linkedMapOf<String, String>().apply {
                meta("og:site_name")?.let { put("Site Name", it) }
                meta("article:author")?.let { put("Author", it) }
                meta("article:published_time")?.let { put("Published", it) }
                meta("product:brand", "og:brand")?.let { put("Brand", it) }
            }
        )
        return item.takeIf { it.isMeaningful() }
    }

    private val PRICE_REGEX = Regex("(\u20b9|\\$|\u20ac|\u00a3)\\s?[0-9][0-9,.]*")
    private val RATING_REGEX = Regex("[0-5](\\.[0-9])?\\s*(out of\\s*5|stars|\u2605)", RegexOption.IGNORE_CASE)

    /**
     * Converts a list of items (optionally from several pages) into export rows with a header.
     * No fixed column list beyond the 7 core fields: extra columns are the union of whatever
     * [ScrapedItem.extra] fields were actually detected across the batch, appended after the
     * fixed columns so [IMAGE_COLUMN_INDEX] stays valid - and blank wherever a given item
     * didn't have that particular field.
     */
    fun toRows(items: List<ScrapedItem>, includeSourceColumn: Boolean = false, sources: List<String> = emptyList()): List<List<String>> {
        val header = mutableListOf("Name", "Description", "Price", "Rating", "Link", "Image", "Category")
        if (includeSourceColumn) header.add("Source URL")

        val extraColumns = LinkedHashSet<String>()
        for (item in items) extraColumns.addAll(item.extra.keys)
        header.addAll(extraColumns)

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
            for (column in extraColumns) row.add(item.extra[column].orEmpty())
            row
        }
        return listOf(header) + rows
    }
}
