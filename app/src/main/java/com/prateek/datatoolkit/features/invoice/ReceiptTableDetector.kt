package com.prateek.datatoolkit.features.invoice

import com.prateek.datatoolkit.features.ocr.OcrLine

/**
 * Finds and extracts a receipt/invoice's item table directly from OCR *layout* - which words
 * sit on which line, and where each one sits horizontally - instead of assuming any single
 * template, fixed column order, or fixed keyword set.
 *
 * Two extraction paths, in order of confidence:
 *
 * 1. **Header-driven** ([detectWithHeader]): if a row can be identified as a header (its
 *    words independently match at least two different column roles - Item/Qty/Unit Price/
 *    Amount - against broad synonym lists, not one fixed phrase), that row's word x-positions
 *    define column *bands*. Every row below is measured against those bands, not against its
 *    own guess, so column order and naming can vary freely between receipts: a
 *    "Qty | Item | Rate | Line Total" header and a "Description | Amount" header both resolve
 *    correctly because roles are matched by content, not position.
 * 2. **Content-pattern fallback** ([detectWithoutHeader]): when no header row is found, each
 *    row is decomposed right-to-left by what it contains - a money-shaped token is Amount, a
 *    second money-shaped token before it is Unit Price, a bare short integer is Qty, and
 *    whatever's left is the Item description. Lower confidence, but still driven by what a
 *    row actually contains rather than a guessed fixed layout.
 *
 * In both paths, a row that turns out to have nothing but description text (no qty/price/
 * amount at all) is treated as a wrapped continuation of the *previous* row's description -
 * common when an item name or a modifier ("- extra hot") wraps onto its own line - rather
 * than becoming a bogus amount-less line item. This is the "row alignment" check: it's what
 * keeps a multi-line description from being misread as an extra phantom row.
 *
 * Whatever isn't a table (metadata above it, subtotal/tax/total and payment lines below it)
 * is left alone - [InvoiceParser] already extracts those; this class only ever contributes
 * [InvoiceLineItem]s. Missing columns are simply left blank on every item, never guessed -
 * and the same goes for a Qty/Unit Price/Amount cell that IS present but doesn't parse as a
 * number (OCR noise, a misaligned neighbour): blanked rather than kept or moved elsewhere.
 */
object ReceiptTableDetector {

    private enum class ColumnRole { ITEM, QTY, UNIT_PRICE, AMOUNT }

    data class DetectedTable(
        val items: List<InvoiceLineItem>,
        /** True if a header row was confidently identified (the high-confidence path ran). */
        val headerFound: Boolean,
        /** False if enough rows failed to line up with the detected columns that the result
         *  is worth flagging for a closer look - still returned (never dropped), just noted. */
        val wellAligned: Boolean
    )

    /** Broad, synonym-based - not one fixed phrase - so "Item"/"Description"/"Product",
     *  "Qty"/"Quantity"/"Units", "Price"/"Rate"/"MRP" and "Amount"/"Total"/"Line Total" (etc.)
     *  all resolve to the same role regardless of which one a particular receipt prints. */
    private val ROLE_SYNONYMS: Map<ColumnRole, Set<String>> = mapOf(
        ColumnRole.ITEM to setOf("item", "items", "description", "desc", "product", "particulars", "article", "name"),
        ColumnRole.QTY to setOf("qty", "quantity", "units", "unit", "nos", "no", "count", "pcs"),
        ColumnRole.UNIT_PRICE to setOf("price", "rate", "unitprice", "unit price", "mrp", "each", "priceeach"),
        ColumnRole.AMOUNT to setOf("amount", "total", "amt", "value", "linetotal", "line total", "netamount", "net amount")
    )

    /** Lines that mark the end of the item table / are never read as an item row even if they
     *  happen to contain a money-shaped number. [InvoiceParser]'s own text-only fallback path
     *  uses this same list, so the two extraction strategies agree on where a table ends. */
    internal val FOOTER_KEYWORDS = listOf(
        "total", "tax", "gst", "vat", "discount", "round off", "rounding",
        "service charge", "delivery charge", "delivery fee", "shipping",
        "change due", "cash tendered", "amount tendered", "balance due", "amount due",
        "subtotal", "sub total", "sub-total", "payment", "thank you", "thanks for"
    )

    private val moneyToken = Regex("""^[₹$€£]?\s*[\d,]+\.\d{1,2}$""")
    private val intToken = Regex("""^\d{1,4}$""")

    fun detect(lines: List<OcrLine>): DetectedTable {
        val rows = lines.filter { it.words.isNotEmpty() }
        if (rows.isEmpty()) return DetectedTable(emptyList(), headerFound = false, wellAligned = true)

        val headerIndex = findHeaderRow(rows)
        return if (headerIndex != null) detectWithHeader(rows, headerIndex) else detectWithoutHeader(rows)
    }

    // ---- Header-driven extraction (high confidence path) --------------------------------------

    /** The best-scoring row - by count of distinct roles matched - wins, provided it matches
     *  at least two different roles (one match alone, e.g. a lone "Total" on the footer line,
     *  isn't enough to call a row a header). */
    private fun findHeaderRow(rows: List<OcrLine>): Int? {
        var bestIndex: Int? = null
        var bestScore = 0
        for ((index, row) in rows.withIndex()) {
            val rolesMatched = mergeIntoCells(row).mapNotNull { matchRole(it.text) }.toSet()
            if (rolesMatched.size > bestScore) {
                bestScore = rolesMatched.size
                bestIndex = index
            }
        }
        return if (bestScore >= 2) bestIndex else null
    }

    /** [value] unchanged if it parses as a number (the same contract as
     *  [InvoiceParser.parseNumericValue]), blank otherwise - keeps a header-band-matched
     *  Qty/Unit Price/Amount cell only when it's actually numeric, instead of passing OCR
     *  noise (or a neighbouring column's misaligned text) through as-is. */
    private fun numericOrBlank(value: String): String =
        if (InvoiceParser.parseNumericValue(value) != null) value else ""

    private fun detectWithHeader(rows: List<OcrLine>, headerIndex: Int): DetectedTable {
        val bands = buildBands(mergeIntoCells(rows[headerIndex]))
        if (bands.roles.isEmpty()) return DetectedTable(emptyList(), headerFound = true, wellAligned = true)

        val bodyEnd = findBodyEnd(rows, headerIndex + 1)
        val items = mutableListOf<InvoiceLineItem>()
        var misaligned = 0
        var consideredRows = 0

        for (rowIndex in (headerIndex + 1) until bodyEnd) {
            val row = rows[rowIndex]
            if (FOOTER_KEYWORDS.any { row.text.lowercase().contains(it) }) continue
            val cells = mergeIntoCells(row)
            if (cells.isEmpty()) continue
            consideredRows++

            val valuesByRole = LinkedHashMap<ColumnRole, MutableList<String>>()
            for (cell in cells) {
                val role = bands.roleFor(cell.centerX) ?: continue
                valuesByRole.getOrPut(role) { mutableListOf() }.add(cell.text)
            }
            // A band-matched cell that doesn't actually parse as a number is OCR noise, not
            // a trustworthy Qty/Price/Amount - and per the "never shift a value into another
            // column" rule, the fix is to blank it here, not reassign it elsewhere.
            val amount = numericOrBlank(valuesByRole[ColumnRole.AMOUNT]?.joinToString(" ").orEmpty())
            val qty = numericOrBlank(valuesByRole[ColumnRole.QTY]?.joinToString(" ").orEmpty())
            val unitPrice = numericOrBlank(valuesByRole[ColumnRole.UNIT_PRICE]?.joinToString(" ").orEmpty())
            val item = valuesByRole[ColumnRole.ITEM]?.joinToString(" ").orEmpty()

            if (amount.isBlank() && qty.isBlank() && unitPrice.isBlank()) {
                // Nothing outside the description band - a wrapped continuation of the
                // previous row's item text (a long name or a "- extra hot" modifier line),
                // not a new (amount-less) item. This is the row-alignment check in action.
                if (item.isNotBlank() && items.isNotEmpty()) {
                    val prev = items.removeAt(items.size - 1)
                    items.add(prev.copy(description = (prev.description + " " + item).trim()))
                }
                continue
            }
            if (amount.isBlank() && item.isBlank()) { misaligned++; continue }
            if (item.isBlank()) misaligned++

            items.add(InvoiceLineItem(description = item, quantity = qty, unitPrice = unitPrice, amount = amount))
        }

        val wellAligned = consideredRows == 0 || misaligned.toDouble() / consideredRows <= 0.34
        return DetectedTable(items, headerFound = true, wellAligned = wellAligned)
    }

    /** How far past the header (or, in the no-header path, past the first item-like row) the
     *  table plausibly extends: stops at the first footer-keyword line, or at a row whose gap
     *  from the previous one is unusually large for this table - typically a rule or blank
     *  space before the totals block, even on a receipt whose totals lines don't literally
     *  say "total". */
    private fun findBodyEnd(rows: List<OcrLine>, start: Int): Int {
        if (start >= rows.size) return start
        val gaps = mutableListOf<Int>()
        for (i in start until rows.size) {
            if (FOOTER_KEYWORDS.any { rows[i].text.lowercase().contains(it) }) return i
            if (i > start) {
                val gap = (rows[i].top - rows[i - 1].bottom).coerceAtLeast(0)
                if (gaps.size >= 2) {
                    val median = gaps.sorted()[gaps.size / 2]
                    if (gap > median * 3 && gap > rows[i].height * 2) return i
                }
                gaps.add(gap)
            }
        }
        return rows.size
    }

    // ---- No-header fallback (best effort) ------------------------------------------------------

    private fun detectWithoutHeader(rows: List<OcrLine>): DetectedTable {
        val startIndex = rows.indexOfFirst { row ->
            !FOOTER_KEYWORDS.any { row.text.lowercase().contains(it) } &&
                mergeIntoCells(row).any { moneyToken.matches(it.text) }
        }
        if (startIndex == -1) return DetectedTable(emptyList(), headerFound = false, wellAligned = true)
        val bodyEnd = findBodyEnd(rows, startIndex)

        val items = mutableListOf<InvoiceLineItem>()
        var misaligned = 0
        var consideredRows = 0

        for (rowIndex in startIndex until bodyEnd) {
            val row = rows[rowIndex]
            if (FOOTER_KEYWORDS.any { row.text.lowercase().contains(it) }) continue
            val cells = mergeIntoCells(row)
            if (cells.isEmpty()) continue

            val moneyIdx = cells.indices.filter { moneyToken.matches(cells[it].text) }
            if (moneyIdx.isEmpty()) {
                // No price at all on this row - almost always a wrapped description line.
                if (items.isNotEmpty()) {
                    val prev = items.removeAt(items.size - 1)
                    items.add(prev.copy(description = (prev.description + " " + row.text).trim()))
                }
                continue
            }
            consideredRows++
            val amountIdx = moneyIdx.last()
            val unitPriceIdx = moneyIdx.dropLast(1).lastOrNull()
            val qtyIdx = cells.indices.firstOrNull { i -> i != amountIdx && i != unitPriceIdx && intToken.matches(cells[i].text) }
            val usedIdx = setOfNotNull(amountIdx, unitPriceIdx, qtyIdx)
            val description = cells.indices.filter { it !in usedIdx }.joinToString(" ") { cells[it].text }.trim()

            if (description.isBlank()) { misaligned++; continue }
            items.add(
                InvoiceLineItem(
                    description = description,
                    quantity = qtyIdx?.let { cells[it].text }.orEmpty(),
                    unitPrice = unitPriceIdx?.let { cells[it].text }.orEmpty(),
                    amount = cells[amountIdx].text
                )
            )
        }
        val wellAligned = consideredRows == 0 || misaligned.toDouble() / consideredRows <= 0.34
        return DetectedTable(items, headerFound = false, wellAligned = wellAligned)
    }

    // ---- Shared geometry helpers ----------------------------------------------------------------

    private data class Cell(val text: String, val left: Int, val right: Int) {
        val centerX: Int get() = (left + right) / 2
    }

    /** Merges a line's individually-recognized words into visual "cells" - groups of words
     *  close enough together to be one column's content (e.g. "Unit" + "Price", or a two-word
     *  item name) - splitting only on gaps wide enough to plausibly be a real column boundary.
     *  The threshold scales with the row's own text height, so it adapts to the photo's
     *  resolution instead of assuming a fixed pixel gap. This - real pixel gaps between word
     *  boxes - is what column detection is based on, not the number of space characters ML
     *  Kit happened to put in the flattened text for a given gap. */
    private fun mergeIntoCells(row: OcrLine): List<Cell> {
        val words = row.words.sortedBy { it.left }
        if (words.isEmpty()) return emptyList()
        val gapThreshold = maxOf(18, (row.height * 0.9).toInt())
        val cells = mutableListOf<Cell>()
        var curText = StringBuilder(words[0].text)
        var curLeft = words[0].left
        var curRight = words[0].right
        for (i in 1 until words.size) {
            val w = words[i]
            if (w.left - curRight > gapThreshold) {
                cells.add(Cell(curText.toString(), curLeft, curRight))
                curText = StringBuilder(w.text)
                curLeft = w.left
                curRight = w.right
            } else {
                curText.append(' ').append(w.text)
                curRight = maxOf(curRight, w.right)
            }
        }
        cells.add(Cell(curText.toString(), curLeft, curRight))
        return cells
    }

    /** Column boundaries derived from the header row's cell centers (midpoint between each
     *  adjacent pair), extended to +/-infinity at the ends so a body-row cell shifted slightly
     *  outside the header's own span - common with OCR jitter - still lands in the nearest
     *  column instead of being orphaned. */
    private data class Bands(val boundaries: List<Int>, val roles: List<ColumnRole?>) {
        fun roleFor(centerX: Int): ColumnRole? {
            var idx = 0
            while (idx < boundaries.size && centerX > boundaries[idx]) idx++
            return roles.getOrNull(idx)
        }
    }

    private fun buildBands(headerCells: List<Cell>): Bands {
        if (headerCells.isEmpty()) return Bands(emptyList(), emptyList())
        val roles = headerCells.map { matchRole(it.text) }.toMutableList()
        // Some receipts leave the description column unlabeled since it's implied - if no
        // cell matched ITEM by name, the leftmost column is it by position instead.
        if (ColumnRole.ITEM !in roles) {
            val leftmost = headerCells.indices.minByOrNull { headerCells[it].left } ?: 0
            if (roles[leftmost] == null) roles[leftmost] = ColumnRole.ITEM
        }
        val boundaries = (0 until headerCells.size - 1).map { i -> (headerCells[i].centerX + headerCells[i + 1].centerX) / 2 }
        return Bands(boundaries, roles)
    }

    private fun matchRole(cellText: String): ColumnRole? {
        val normalized = cellText.lowercase().trim().trim(':', '.', '-')
        for ((role, synonyms) in ROLE_SYNONYMS) {
            if (synonyms.any { syn -> normalized == syn || normalized.replace(" ", "") == syn.replace(" ", "") }) return role
        }
        return null
    }
}
