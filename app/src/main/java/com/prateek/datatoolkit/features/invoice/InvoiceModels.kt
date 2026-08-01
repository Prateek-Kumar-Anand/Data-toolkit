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

/**
 * Everything [InvoiceParser] could pull out of one scanned invoice/receipt, plus the raw
 * OCR text it came from. Every field is a plain, editable String (not a validated/typed
 * amount) because this is heuristic, on-device, regex-based extraction - not a cloud
 * document-AI service - so the user is always shown what was found and can correct any
 * field before it's added to the batch or exported.
 */
data class ParsedInvoice(
    var invoiceNumber: String = "",
    var date: String = "",
    var customerName: String = "",
    var items: List<InvoiceLineItem> = emptyList(),
    var subtotal: String = "",
    var tax: String = "",
    var total: String = "",
    var sourceLabel: String = "",
    val rawText: String = ""
) {
    /** True once at least one field beyond the raw text was actually detected. */
    fun hasAnyDetectedField(): Boolean =
        invoiceNumber.isNotBlank() || date.isNotBlank() || customerName.isNotBlank() ||
            items.isNotEmpty() || subtotal.isNotBlank() || tax.isNotBlank() || total.isNotBlank()
}
