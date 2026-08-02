package com.prateek.datatoolkit.features.invoice

/**
 * Turns raw OCR text from a photographed invoice/receipt into a [ParsedInvoice]. Entirely
 * on-device, regex/heuristic-based (no network call, no cloud document-AI service) - the
 * same "no server round trip" philosophy as the rest of the app's OCR/scraping/cleaning
 * tools.
 *
 * There is no fixed column mapping. A restaurant bill, a retail receipt, and a freelance
 * invoice all print a different set of labeled fields - one module handles all of them by
 * scanning every line for a "label -> value" pattern instead of assuming any particular
 * field will be present. [FIELD_SPECS] lists the field names this parser actively looks
 * for (with their common label spellings/synonyms so "Invoice No", "Bill No" and "Order #"
 * all resolve to the same canonical "Invoice Number" column); anything labeled that isn't
 * on that list - GSTIN, CGST/SGST, a discount line, a payment mode, a table number, a
 * cashier name, whatever a particular format happens to print - still gets picked up by
 * the generic fallback pass and added under its own detected name, so nothing is lost to a
 * hardcoded schema. Every field the caller ends up with came from [ParsedInvoice.fields],
 * so the set of Excel columns produced downstream is only ever whatever was actually
 * detected on that batch of scans - never a fixed template.
 *
 * Like any OCR-driven pipeline this can miss or misread unusual layouts, which is exactly
 * why every field stays editable in the UI before being added to a batch or exported.
 */
object InvoiceParser {

    private val moneyPattern = Regex("""\$?\s*([\d,]+\.\d{2})""")

    private enum class FieldKind {
        /** A short alphanumeric code on the same line as its label, e.g. "Invoice No: INV-2045". */
        ID_LIKE,
        /** Free text that may follow the label on the same line, or sit on the next line
         *  when the label appears alone (e.g. a "Bill To:" line followed by the name). */
        NAME_LIKE,
        /** A money value on the line, taking the last (rightmost) amount found. */
        AMOUNT
    }

    private data class FieldSpec(
        val canonicalName: String,
        val labels: List<String>,
        val kind: FieldKind,
        val excludeIfLineContains: List<String> = emptyList()
    )

    /** Every field this parser actively looks for, in detection order. Order matters for
     *  the AMOUNT fields in particular - Subtotal is matched before Total so a "Total"
     *  pattern doesn't also grab the subtotal line, and the GST breakdown is matched before
     *  the generic "Tax" pattern so "CGST"/"SGST" don't fall through into a plain Tax field. */
    private val FIELD_SPECS = listOf(
        FieldSpec(CoreInvoiceFields.COMPANY, listOf("company name", "company", "business name", "billed by", "store name", "vendor", "seller", "merchant name", "merchant"), FieldKind.NAME_LIKE),
        FieldSpec(CoreInvoiceFields.INVOICE_NUMBER, listOf("invoice", "receipt", "bill", "order", "txn", "transaction", "reference", "ref"), FieldKind.ID_LIKE),
        FieldSpec("GSTIN", listOf("gstin", "gst no", "gst number", "tax id", "tin", "vat no"), FieldKind.ID_LIKE),
        FieldSpec("Phone", listOf("phone", "contact no", "contact", "mobile", "tel"), FieldKind.ID_LIKE),
        FieldSpec("Table", listOf("table no", "table"), FieldKind.ID_LIKE),
        FieldSpec(CoreInvoiceFields.CUSTOMER, listOf("bill to", "sold to", "customer name", "customer", "client", "buyer"), FieldKind.NAME_LIKE),
        FieldSpec("Cashier", listOf("cashier", "served by"), FieldKind.NAME_LIKE),
        FieldSpec("Payment Mode", listOf("payment mode", "paid via", "payment method", "mode of payment"), FieldKind.NAME_LIKE),
        FieldSpec(CoreInvoiceFields.SUBTOTAL, listOf("subtotal", "sub total", "sub-total", "taxable value", "taxable amount", "net amount"), FieldKind.AMOUNT),
        FieldSpec("CGST", listOf("cgst"), FieldKind.AMOUNT),
        FieldSpec("SGST", listOf("sgst"), FieldKind.AMOUNT),
        FieldSpec("IGST", listOf("igst"), FieldKind.AMOUNT),
        FieldSpec(CoreInvoiceFields.TAX, listOf("tax", "gst", "vat", "sales tax", "service tax"), FieldKind.AMOUNT, excludeIfLineContains = listOf("tax id", "taxid", "tin", "gstin", "vat no")),
        FieldSpec("Discount", listOf("discount"), FieldKind.AMOUNT),
        FieldSpec("Delivery Charge", listOf("delivery charge", "delivery fee", "shipping"), FieldKind.AMOUNT),
        FieldSpec("Service Charge", listOf("service charge"), FieldKind.AMOUNT),
        FieldSpec("Round Off", listOf("round off", "rounding"), FieldKind.AMOUNT),
        FieldSpec("Change", listOf("change due", "change", "cash tendered", "amount tendered"), FieldKind.AMOUNT),
        FieldSpec(CoreInvoiceFields.TOTAL, listOf("grand total", "amount due", "balance due", "total due", "net payable", "amount payable", "total"), FieldKind.AMOUNT, excludeIfLineContains = listOf("subtotal", "sub total", "sub-total"))
    )

    private val datePatterns = listOf(
        Regex("""\b(\d{1,2}[\/\-.]\d{1,2}[\/\-.]\d{2,4})\b"""),
        Regex("""\b(\d{4}[\/\-.]\d{1,2}[\/\-.]\d{1,2})\b"""),
        Regex("""(?i)\b((?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+\d{1,2},?\s+\d{4})\b"""),
        Regex("""(?i)\b(\d{1,2}\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?,?\s+\d{4})\b""")
    )

    /** Safe generic fallback: any still-unclaimed "Label: value" line, colon required so it
     *  doesn't collide with item rows (receipts almost never punctuate an item line this
     *  way). Catches whatever field names [FIELD_SPECS] doesn't know about by name, so a
     *  format this parser has never seen still contributes its labeled fields instead of
     *  being silently dropped. */
    private val genericLabelPattern = Regex("""^\s*([A-Za-z][A-Za-z .]{1,28}?)\s*[:]\s*(.+\S)\s*$""")

    private val itemExcludeWords = listOf(
        "total", "tax", "gst", "vat", "discount", "round off", "rounding",
        "service charge", "delivery charge", "delivery fee", "shipping",
        "change due", "cash tendered", "amount tendered", "balance due", "amount due"
    )

    fun parse(rawText: String, sourceLabel: String = ""): ParsedInvoice {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val consumed = mutableSetOf<Int>()
        val fields = LinkedHashMap<String, String>()

        for (spec in FIELD_SPECS) {
            val value = extractField(lines, spec, consumed) ?: continue
            fields[spec.canonicalName] = value
        }

        // Most receipts print the business/company name unlabeled as the very first line -
        // unlike everything in FIELD_SPECS above, there's usually no "Company:" tag to key
        // off. If nothing already claimed that role, fall back to whichever of the first few
        // lines looks like a plausible business name rather than boilerplate ("Tax Invoice"),
        // a date, or a money line.
        if (!fields.containsKey(CoreInvoiceFields.COMPANY)) {
            val boilerplateLines = setOf(
                "invoice", "tax invoice", "receipt", "cash memo", "bill", "order summary", "estimate", "quotation"
            )
            for ((index, line) in lines.withIndex()) {
                if (index >= 3) break
                if (index in consumed) continue
                val trimmed = line.trim()
                if (trimmed.lowercase() in boilerplateLines || trimmed.length > 60) continue
                if (moneyPattern.containsMatchIn(trimmed)) continue
                if (datePatterns.any { it.containsMatchIn(trimmed) }) continue
                fields[CoreInvoiceFields.COMPANY] = trimmed
                consumed.add(index)
                break
            }
        }

        extractDate(lines, consumed)?.let { fields[CoreInvoiceFields.DATE] = it }

        // Line items next, before the generic fallback below - a receipt that writes items
        // as "Coffee: 3.50" should still end up as a line item, not get swallowed as a
        // one-off "Coffee" field just because it happens to use a colon.
        val items = extractLineItems(lines, consumed)

        // Anything still unclaimed that looks like "Label: value" is a field this parser
        // doesn't have a synonym list for - keep it under its own detected name rather than
        // dropping it, so an unfamiliar receipt format still yields useful columns.
        for ((index, line) in lines.withIndex()) {
            if (index in consumed) continue
            val match = genericLabelPattern.find(line) ?: continue
            val label = titleCase(match.groupValues[1].trim())
            val value = match.groupValues[2].trim()
            if (label.isBlank() || value.isBlank() || label.length > 30) continue
            if (fields.containsKey(label)) continue
            fields[label] = value
            consumed.add(index)
        }

        return ParsedInvoice(fields = fields, items = items, sourceLabel = sourceLabel, rawText = rawText)
    }

    private fun buildLabelPattern(label: String): String =
        label.split(" ").joinToString("""\s+""") { Regex.escape(it) }

    private fun extractField(lines: List<String>, spec: FieldSpec, consumed: MutableSet<Int>): String? {
        for ((index, line) in lines.withIndex()) {
            if (index in consumed) continue
            val lower = line.lowercase()
            if (spec.excludeIfLineContains.any { lower.contains(it) }) continue
            val label = spec.labels.firstOrNull { candidate ->
                Regex("""(?i)\b${buildLabelPattern(candidate)}\b""").containsMatchIn(line)
            } ?: continue

            when (spec.kind) {
                FieldKind.AMOUNT -> {
                    val matches = moneyPattern.findAll(line).toList()
                    if (matches.isEmpty()) continue
                    consumed.add(index)
                    return matches.last().groupValues[1].trim()
                }
                FieldKind.ID_LIKE -> {
                    val pattern = Regex(
                        """(?i)\b${buildLabelPattern(label)}\b(?:\s*(?:no\.?|num(?:ber)?)\s*[:#]?|[\s:#]*[:#])[\s:#]*([A-Za-z0-9][A-Za-z0-9\-\/]{1,})"""
                    )
                    val match = pattern.find(line) ?: continue
                    consumed.add(index)
                    return match.groupValues[1].trim().trim('.', ',')
                }
                FieldKind.NAME_LIKE -> {
                    val pattern = Regex("""(?i)^\s*${buildLabelPattern(label)}\s*[:\-]?\s*(.*)$""")
                    val match = pattern.find(line) ?: continue
                    consumed.add(index)
                    val sameLine = match.groupValues[1].trim()
                    if (sameLine.isNotBlank()) return sameLine
                    // Label with nothing after it (e.g. just "Bill To:") - the value is
                    // usually the very next non-blank line in this style of layout.
                    val nextIndex = index + 1
                    if (nextIndex < lines.size && nextIndex !in consumed) {
                        consumed.add(nextIndex)
                        return lines[nextIndex]
                    }
                    return null
                }
            }
        }
        return null
    }

    private fun extractDate(lines: List<String>, consumed: MutableSet<Int>): String? {
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
        return null
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
            if (itemExcludeWords.any { lower.contains(it) }) continue

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

    private fun titleCase(label: String): String =
        label.lowercase().split(" ").filter { it.isNotBlank() }.joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
}
