package com.prateek.datatoolkit.features.invoice

/**
 * Turns raw OCR text from a photographed invoice/receipt into structured [ParsedInvoice]
 * fields. This is entirely on-device, regex/heuristic-based (no network call, no cloud
 * document-AI service) - the same "no server round trip" philosophy as the rest of the
 * app's OCR/scraping/cleaning tools. It works well on common, clearly-labeled invoice
 * layouts ("Invoice #: ...", "Bill To: ...", "Total: $...") but - like any OCR-driven
 * pipeline - can miss or misread unusual layouts, which is exactly why every field stays
 * editable in the UI before being added to a batch or exported.
 */
object InvoiceParser {

    private val moneyPattern = Regex("""\$?\s*([\d,]+\.\d{2})""")

    private val invoiceNumberPatterns = listOf(
        Regex("""(?i)\binvoice\b(?:\s*(?:no\.?|num(?:ber)?)\s*[:#]?|[\s:#]*[:#])[\s:#]*([A-Za-z0-9][A-Za-z0-9\-\/]{1,})"""),
        Regex("""(?i)\breceipt\b(?:\s*(?:no\.?|num(?:ber)?)\s*[:#]?|[\s:#]*[:#])[\s:#]*([A-Za-z0-9][A-Za-z0-9\-\/]{1,})"""),
        Regex("""(?i)\bbill\b(?:\s*(?:no\.?|num(?:ber)?)\s*[:#]?|[\s:#]*[:#])[\s:#]*([A-Za-z0-9][A-Za-z0-9\-\/]{1,})"""),
        Regex("""(?i)\border\b(?:\s*(?:no\.?|num(?:ber)?)\s*[:#]?|[\s:#]*[:#])[\s:#]*([A-Za-z0-9][A-Za-z0-9\-\/]{1,})""")
    )

    private val datePatterns = listOf(
        Regex("""\b(\d{1,2}[\/\-.]\d{1,2}[\/\-.]\d{2,4})\b"""),
        Regex("""\b(\d{4}[\/\-.]\d{1,2}[\/\-.]\d{1,2})\b"""),
        Regex("""(?i)\b((?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+\d{1,2},?\s+\d{4})\b"""),
        Regex("""(?i)\b(\d{1,2}\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?,?\s+\d{4})\b""")
    )

    private val customerLabelPattern = Regex("""(?i)^\s*(?:bill\s*to|sold\s*to|customer|client|buyer)\s*[:\-]?\s*(.*)$""")

    fun parse(rawText: String, sourceLabel: String = ""): ParsedInvoice {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val consumedLineIndexes = mutableSetOf<Int>()

        val invoiceNumber = firstLabeledMatch(lines, invoiceNumberPatterns, consumedLineIndexes)
        val date = extractDate(lines, consumedLineIndexes)
        val customer = extractCustomer(lines, consumedLineIndexes)
        val subtotal = extractAmount(lines, listOf("subtotal", "sub total", "sub-total"), emptyList(), consumedLineIndexes)
        val tax = extractAmount(lines, listOf("tax", "gst", "vat", "sales tax"), listOf("tax id", "taxid", "tin", "gstin", "vat no"), consumedLineIndexes)
        val total = extractAmount(
            lines,
            listOf("grand total", "amount due", "balance due", "total due", "total"),
            listOf("subtotal", "sub total", "sub-total"),
            consumedLineIndexes
        )
        val items = extractLineItems(lines, consumedLineIndexes)

        return ParsedInvoice(
            invoiceNumber = invoiceNumber,
            date = date,
            customerName = customer,
            items = items,
            subtotal = subtotal,
            tax = tax,
            total = total,
            sourceLabel = sourceLabel,
            rawText = rawText
        )
    }

    private fun firstLabeledMatch(lines: List<String>, patterns: List<Regex>, consumed: MutableSet<Int>): String {
        for ((index, line) in lines.withIndex()) {
            for (pattern in patterns) {
                val match = pattern.find(line)
                if (match != null) {
                    consumed.add(index)
                    return match.groupValues[1].trim().trim('.', ',')
                }
            }
        }
        return ""
    }

    private fun extractDate(lines: List<String>, consumed: MutableSet<Int>): String {
        // Pass 1: a line explicitly labeled as a date, that isn't a due date.
        for ((index, line) in lines.withIndex()) {
            if (index in consumed) continue
            val lower = line.lowercase()
            if (lower.contains("date") && !lower.contains("due")) {
                for (pattern in datePatterns) {
                    val match = pattern.find(line)
                    if (match != null) {
                        consumed.add(index)
                        return match.groupValues[1].trim()
                    }
                }
            }
        }
        // Pass 2: fall back to the first date-like token anywhere.
        for ((index, line) in lines.withIndex()) {
            if (index in consumed) continue
            for (pattern in datePatterns) {
                val match = pattern.find(line)
                if (match != null) {
                    consumed.add(index)
                    return match.groupValues[1].trim()
                }
            }
        }
        return ""
    }

    private fun extractCustomer(lines: List<String>, consumed: MutableSet<Int>): String {
        for ((index, line) in lines.withIndex()) {
            if (index in consumed) continue
            val match = customerLabelPattern.find(line) ?: continue
            consumed.add(index)
            val sameLine = match.groupValues[1].trim()
            if (sameLine.isNotBlank()) return sameLine
            // Label with nothing after it (e.g. just "Bill To:") - the name is usually the
            // very next non-blank line in this style of layout.
            val nextIndex = index + 1
            if (nextIndex < lines.size && nextIndex !in consumed) {
                consumed.add(nextIndex)
                return lines[nextIndex]
            }
        }
        return ""
    }

    private fun extractAmount(
        lines: List<String>,
        includeLabels: List<String>,
        excludeLabels: List<String>,
        consumed: MutableSet<Int>
    ): String {
        for ((index, line) in lines.withIndex()) {
            if (index in consumed) continue
            val lower = line.lowercase()
            if (excludeLabels.any { lower.contains(it) }) continue
            if (includeLabels.none { lower.contains(it) }) continue
            val matches = moneyPattern.findAll(line).toList()
            if (matches.isNotEmpty()) {
                consumed.add(index)
                return matches.last().groupValues[1].trim()
            }
        }
        return ""
    }

    /**
     * Whatever's left after header fields are pulled out: any remaining line that ends in a
     * money-formatted amount is treated as one line item. If the line splits into 3+ columns
     * (by 2+ spaces or a tab - common when OCR preserves a table's column gaps), the middle
     * columns are read as quantity/unit price; otherwise just the description + amount.
     */
    private fun extractLineItems(lines: List<String>, consumed: MutableSet<Int>): List<InvoiceLineItem> {
        val items = mutableListOf<InvoiceLineItem>()
        for ((index, line) in lines.withIndex()) {
            if (index in consumed) continue
            val moneyMatches = moneyPattern.findAll(line).toList()
            if (moneyMatches.isEmpty()) continue
            val lower = line.lowercase()
            if (lower.contains("total") || lower.contains("tax") || lower.contains("gst") || lower.contains("vat")) continue

            val amount = moneyMatches.last().groupValues[1].trim()
            val columns = line.split(Regex("""\s{2,}|\t""")).map { it.trim() }.filter { it.isNotBlank() }

            if (columns.size >= 3) {
                val description = columns.dropLast(1).firstOrNull { col -> col.toIntOrNull() == null && moneyPattern.matchEntire(col) == null }
                    ?: columns.first()
                val qty = columns.firstOrNull { it.toIntOrNull() != null }.orEmpty()
                val unitPrice = columns.dropLast(1).lastOrNull { moneyPattern.matchEntire(it.removePrefix("$").trim()) != null }.orEmpty()
                items.add(InvoiceLineItem(description = description, quantity = qty, unitPrice = unitPrice, amount = amount))
            } else {
                val description = line.substring(0, moneyMatches.last().range.first).trim().trim('-', ':', '|').trim()
                if (description.isNotBlank()) {
                    items.add(InvoiceLineItem(description = description, amount = amount))
                }
            }
        }
        return items
    }
}
