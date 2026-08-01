package com.prateek.datatoolkit.features.invoice

/** One line item detected on an invoice/receipt. Quantity/unit price are only filled in
 *  when the source line clearly had separate columns for them - otherwise left blank
 *  rather than guessed. */
data class InvoiceLineItem(
    val description: String,
    val quantity: String = "",
    val unitPrice: String = "",
    val amount: String = ""
)

/** Field names [InvoiceParser] always looks for on every receipt/invoice, regardless of
 *  layout. These are shown as dedicated, always-visible fields in the review UI. Anything
 *  else the parser finds - GSTIN, CGST/SGST, discount, round off, payment mode, table
 *  number, whatever a particular store's format happens to print - is *not* on this list,
 *  so it flows into [ParsedInvoice.extraFields] instead of being silently dropped. */
object CoreInvoiceFields {
    const val INVOICE_NUMBER = "Invoice Number"
    const val DATE = "Date"
    const val CUSTOMER = "Customer"
    const val SUBTOTAL = "Subtotal"
    const val TAX = "Tax"
    const val TOTAL = "Total"

    val ORDERED = listOf(INVOICE_NUMBER, DATE, CUSTOMER, SUBTOTAL, TAX, TOTAL)
}

/**
 * Everything [InvoiceParser] could pull out of one scanned invoice/receipt, plus the raw
 * OCR text it came from.
 *
 * Fields are NOT a fixed set of properties - they're a label -> value map, because
 * different receipt/invoice formats print different sets of fields (a restaurant bill has
 * a table number and CGST/SGST; a freelance invoice has a client name and a due date; a
 * retail receipt has a discount and round-off line). [InvoiceParser] detects whatever
 * labeled fields are actually present in a given scan and adds them to [fields] under a
 * canonical name - nothing is hardcoded to a fixed column list, and nothing detected is
 * ever thrown away. Every value is a plain, editable String (not a validated/typed amount)
 * because this is heuristic, on-device, regex-based extraction - not a cloud document-AI
 * service - so the user is always shown what was found and can correct any field before
 * it's added to the batch or exported.
 */
data class ParsedInvoice(
    var fields: LinkedHashMap<String, String> = LinkedHashMap(),
    var items: List<InvoiceLineItem> = emptyList(),
    var sourceLabel: String = "",
    val rawText: String = ""
) {
    private fun setOrClear(key: String, value: String) {
        if (value.isBlank()) fields.remove(key) else fields[key] = value.trim()
    }

    // Convenience accessors for the six fields most receipts/invoices have and that the
    // review UI always shows a dedicated box for. Backed by the same [fields] map that
    // drives Excel export, so editing one of these is indistinguishable from editing any
    // other detected field - there's no separate "fixed" storage underneath.
    var invoiceNumber: String
        get() = fields[CoreInvoiceFields.INVOICE_NUMBER] ?: ""
        set(value) = setOrClear(CoreInvoiceFields.INVOICE_NUMBER, value)
    var date: String
        get() = fields[CoreInvoiceFields.DATE] ?: ""
        set(value) = setOrClear(CoreInvoiceFields.DATE, value)
    var customerName: String
        get() = fields[CoreInvoiceFields.CUSTOMER] ?: ""
        set(value) = setOrClear(CoreInvoiceFields.CUSTOMER, value)
    var subtotal: String
        get() = fields[CoreInvoiceFields.SUBTOTAL] ?: ""
        set(value) = setOrClear(CoreInvoiceFields.SUBTOTAL, value)
    var tax: String
        get() = fields[CoreInvoiceFields.TAX] ?: ""
        set(value) = setOrClear(CoreInvoiceFields.TAX, value)
    var total: String
        get() = fields[CoreInvoiceFields.TOTAL] ?: ""
        set(value) = setOrClear(CoreInvoiceFields.TOTAL, value)

    /** Whatever this particular scan detected beyond the six core fields, in the order it
     *  was found - GSTIN, CGST/SGST, discount, round off, payment mode, table number, or
     *  any other "Label: value" line the parser didn't recognize as a known field. */
    val extraFields: LinkedHashMap<String, String>
        get() {
            val extra = LinkedHashMap<String, String>()
            for ((key, value) in fields) if (key !in CoreInvoiceFields.ORDERED) extra[key] = value
            return extra
        }

    /** True once at least one field beyond the raw text was actually detected. */
    fun hasAnyDetectedField(): Boolean = fields.isNotEmpty() || items.isNotEmpty()
}
