package com.prateek.datatoolkit.features.invoice

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.prateek.datatoolkit.R
import com.prateek.datatoolkit.core.cache.AppDatabase
import com.prateek.datatoolkit.core.cache.CacheManager
import com.prateek.datatoolkit.core.cache.ProcessedItem
import com.prateek.datatoolkit.databinding.ActivityInvoiceOcrBinding
import com.prateek.datatoolkit.features.excel.ExcelCsvHelper
import com.prateek.datatoolkit.features.ocr.OcrHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Invoice & Receipt OCR: photograph or pick invoices/receipts, run the same on-device ML Kit
 * recognizer [OcrHelper] already used by the plain OCR screen, then run [InvoiceParser] over
 * the recognized text to pull out company / invoice number / date / customer / line items /
 * subtotal / tax / total. Every field stays editable before it's added to this session's
 * batch, which can then be exported to Excel/CSV in one go - built for a freelancer
 * processing a stack of client invoices/receipts at once.
 */
class InvoiceOcrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInvoiceOcrBinding
    private lateinit var cache: CacheManager
    private lateinit var db: AppDatabase

    private var cameraImageUri: Uri? = null
    private val batch = mutableListOf<ParsedInvoice>()

    // Field name -> its live EditText, for whatever this scan detected beyond the seven core
    // boxes above (GSTIN, CGST/SGST, discount, payment mode, table number, ...). Rebuilt on
    // every populateFields()/clearFields() call since the set of fields differs per scan.
    private val extraFieldRows = mutableListOf<Pair<String, EditText>>()

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraImageUri != null) processSingle(cameraImageUri!!)
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) processSingle(uri)
    }
    private val pickBatch = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) processBatch(uris)
    }

    private val saveCsvAs = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { exportTo(it, asXlsx = false) }
    }
    private val saveXlsxAs = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri -> uri?.let { exportTo(it, asXlsx = true) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInvoiceOcrBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cache = CacheManager(this)
        db = AppDatabase.get(this)

        binding.btnCamera.setOnClickListener { launchCamera() }
        binding.btnGallery.setOnClickListener { pickImage.launch("image/*") }
        binding.btnBatchPick.setOnClickListener { pickBatch.launch("image/*") }
        binding.btnAddToBatch.setOnClickListener { addCurrentFieldsToBatch() }
        binding.btnExportCsv.setOnClickListener { saveCsvAs.launch("invoices_${System.currentTimeMillis()}.csv") }
        binding.btnExportXlsx.setOnClickListener { saveXlsxAs.launch("invoices_${System.currentTimeMillis()}.xlsx") }

        renderBatch()
        renderHistory()
    }

    private fun launchCamera() {
        val file = File(cacheDir, "invoice_capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        cameraImageUri = uri
        takePicture.launch(uri)
    }

    // ---- Single-image scan: OCR + parse, then let the user review/edit before adding ---------

    private fun processSingle(uri: Uri) {
        setBusy(true)
        clearFields()
        binding.tvStatus.text = "Recognizing text..."
        binding.progressBar.max = 100
        binding.progressBar.progress = 0

        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                } ?: throw IllegalStateException("Could not decode the selected image")

                binding.ivPreview.setImageBitmap(bitmap)

                val results = OcrHelper.recognizeBatch(listOf(bitmap)) { done, total ->
                    binding.progressBar.progress = (done * 100) / total
                    binding.tvStatus.text = "Recognizing text... (${(done * 100) / total}%)"
                }
                binding.tvStatus.text = "Parsing invoice fields..."
                val ocrResult = results.first()
                // Passing the full OcrResult (not just its flattened text) lets the parser
                // detect the item table's structure from where words actually sit on the
                // receipt - see ReceiptTableDetector.
                val parsed = InvoiceParser.parse(ocrResult, sourceLabel = displayNameOf(uri))
                binding.progressBar.progress = 100

                populateFields(parsed)
                val quality = qualityOf(parsed)
                val durationMs = System.currentTimeMillis() - start
                val statusLine = if (parsed.hasAnyDetectedField())
                    "Done — review the fields below, then \"Add to Batch\" (detected ${quality}% of key fields)"
                else "Recognized the text, but couldn't confidently detect invoice fields — fill them in manually below"
                binding.tvStatus.text = if (parsed.itemsValidationNote.isNotBlank())
                    "$statusLine\n${parsed.itemsValidationNote}"
                else statusLine
                binding.btnAddToBatch.isEnabled = true

                recordScan(uri.toString(), displayNameOf(uri), ocrResult.text, quality, "SUCCESS", durationMs)
            } catch (e: Throwable) {
                binding.tvStatus.text = "Scan failed: ${e.message}"
                recordScan(uri.toString(), displayNameOf(uri), "", 0, "FAILED", System.currentTimeMillis() - start)
            } finally {
                setBusy(false)
            }
        }
    }

    // ---- Batch scan: OCR + parse every picked image, adding each straight to the batch -------

    private fun processBatch(uris: List<Uri>) {
        setBusy(true)
        clearFields()
        binding.progressBar.max = 100
        binding.progressBar.progress = 0
        binding.tvStatus.text = "Processing 1 of ${uris.size} (0%)..."

        lifecycleScope.launch {
            var added = 0
            for ((index, uri) in uris.withIndex()) {
                val start = System.currentTimeMillis()
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                    } ?: throw IllegalStateException("Could not decode image ${index + 1}")

                    binding.ivPreview.setImageBitmap(bitmap)
                    val result = OcrHelper.recognize(bitmap)
                    val parsed = InvoiceParser.parse(result, sourceLabel = displayNameOf(uri))
                    batch.add(parsed)
                    added++
                    recordScan(uri.toString(), displayNameOf(uri), result.text, qualityOf(parsed), "SUCCESS", System.currentTimeMillis() - start)
                } catch (e: Throwable) {
                    recordScan(uri.toString(), displayNameOf(uri), "", 0, "FAILED", System.currentTimeMillis() - start)
                }
                val pct = ((index + 1) * 100) / uris.size
                binding.progressBar.progress = pct
                binding.tvStatus.text = "Processing ${index + 1} of ${uris.size} ($pct%)..."
            }
            binding.tvStatus.text = "Batch scan done — $added of ${uris.size} invoice(s) added to the batch below"
            renderBatch()
            renderHistory()
            setBusy(false)
        }
    }

    private fun qualityOf(parsed: ParsedInvoice): Int {
        val fields = listOf(parsed.company, parsed.invoiceNumber, parsed.date, parsed.customerName, parsed.total)
        val detected = fields.count { it.isNotBlank() }
        return (detected * 100) / fields.size
    }

    // ---- Card 2: populate / read / clear the editable fields ---------------------------------

    private var lastParsedRawForBatch: ParsedInvoice? = null

    private fun populateFields(parsed: ParsedInvoice) {
        lastParsedRawForBatch = parsed
        binding.etCompany.setText(parsed.company)
        binding.etInvoiceNumber.setText(parsed.invoiceNumber)
        binding.etDate.setText(parsed.date)
        binding.etCustomer.setText(parsed.customerName)
        binding.etItems.setText(itemsToText(parsed.items))
        binding.etSubtotal.setText(parsed.subtotal)
        binding.etTax.setText(parsed.tax)
        binding.etTotal.setText(parsed.total)
        renderExtraFields(parsed.extraFields)
    }

    private fun clearFields() {
        lastParsedRawForBatch = null
        binding.etCompany.setText("")
        binding.etInvoiceNumber.setText("")
        binding.etDate.setText("")
        binding.etCustomer.setText("")
        binding.etItems.setText("")
        binding.etSubtotal.setText("")
        binding.etTax.setText("")
        binding.etTotal.setText("")
        renderExtraFields(linkedMapOf())
        binding.btnAddToBatch.isEnabled = false
    }

    /** Rebuilds the "Other detected fields" section for whatever this scan found beyond the
     *  six core boxes - a different receipt format can mean a different set of rows every
     *  time, so this is built at runtime rather than laid out fixed in XML. */
    private fun renderExtraFields(extra: LinkedHashMap<String, String>) {
        binding.extraFieldsContainer.removeAllViews()
        extraFieldRows.clear()
        binding.tvExtraFieldsLabel.visibility = if (extra.isEmpty()) View.GONE else View.VISIBLE
        for ((label, value) in extra) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(6) }
            }
            row.addView(TextView(this).apply {
                text = label
                setTextColor(colorOf(R.color.text_secondary))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f)
            })
            val editText = EditText(this).apply {
                setText(value)
                textSize = 13f
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = ContextCompat.getDrawable(this@InvoiceOcrActivity, R.drawable.bg_input_field_focused)
                isSingleLine = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
                    .apply { marginStart = dp(6) }
            }
            row.addView(editText)
            binding.extraFieldsContainer.addView(row)
            extraFieldRows.add(label to editText)
        }
    }

    private fun itemsToText(items: List<InvoiceLineItem>): String =
        items.joinToString("\n") { "${it.description} | ${it.quantity} | ${it.unitPrice} | ${it.amount}" }

    /** Parses the "Item | Qty | Unit Price | Amount" text box back into line items. Every
     *  line is mapped strictly by pipe *position* - part 0 is always Item, part 1 always
     *  Quantity, part 2 always Unit Price, part 3 always Amount - regardless of how many
     *  parts a given line has. A line with fewer than 4 parts (typically one the user typed
     *  by hand rather than one [itemsToText] produced) just leaves the missing trailing
     *  column(s) blank; it never reassigns what *is* present to a different column based on
     *  the count, since which column got skipped would be a guess (e.g. a hand-typed
     *  "Coffee | 3.50" is Item + Qty with Unit Price/Amount blank - not Item + Amount, even
     *  though the latter also reads naturally - because inferring that from the count alone
     *  is exactly the kind of shifting this format needs to avoid). Quantity/Unit Price/
     *  Amount are also validated as numeric here, via the same [InvoiceParser.parseNumericValue]
     *  contract the OCR-driven [ReceiptTableDetector] path uses: a part that isn't a
     *  plausible number is left blank rather than kept as-is or moved to another cell. Row
     *  order matches the text box exactly, top to bottom. */
    private fun textToItems(text: String): List<InvoiceLineItem> =
        text.lines().map { it.trim() }.filter { it.isNotBlank() }.map { line ->
            val parts = line.split("|").map { it.trim() }
            val qty = parts.getOrElse(1) { "" }
            val unitPrice = parts.getOrElse(2) { "" }
            val amount = parts.getOrElse(3) { "" }
            InvoiceLineItem(
                description = parts.getOrElse(0) { "" },
                quantity = if (InvoiceParser.parseNumericValue(qty) != null) qty else "",
                unitPrice = if (InvoiceParser.parseNumericValue(unitPrice) != null) unitPrice else "",
                amount = if (InvoiceParser.parseNumericValue(amount) != null) amount else ""
            )
        }

    private fun addCurrentFieldsToBatch() {
        val entry = ParsedInvoice(
            items = textToItems(binding.etItems.text.toString()),
            sourceLabel = lastParsedRawForBatch?.sourceLabel.orEmpty(),
            rawText = lastParsedRawForBatch?.rawText.orEmpty()
        )
        entry.company = binding.etCompany.text.toString().trim()
        entry.invoiceNumber = binding.etInvoiceNumber.text.toString().trim()
        entry.date = binding.etDate.text.toString().trim()
        entry.customerName = binding.etCustomer.text.toString().trim()
        entry.subtotal = binding.etSubtotal.text.toString().trim()
        entry.tax = binding.etTax.text.toString().trim()
        entry.total = binding.etTotal.text.toString().trim()
        // Whatever extra fields were detected/edited for this scan - could be empty if
        // nothing beyond the six core fields was found on this particular receipt.
        for ((label, editText) in extraFieldRows) {
            val value = editText.text.toString().trim()
            if (value.isNotBlank()) entry.fields[label] = value
        }
        // Re-check items against totals using whatever the user ended up with here - they may
        // have hand-edited the items box, so the exported "Totals Check" column should reflect
        // what's actually being added, not the original OCR pass's now-possibly-stale result.
        val validation = InvoiceParser.validateItems(entry.items, entry.fields)
        entry.itemsValidationNote = validation.note
        entry.itemsNeedReview = validation.needsReview
        batch.add(entry)
        renderBatch()
        clearFields()
        binding.tvStatus.text = "Added to batch (${batch.size} total) — scan another, or export below"
        Toast.makeText(this, "Added to batch", Toast.LENGTH_SHORT).show()
    }

    // ---- Card 3: session batch list + export --------------------------------------------------

    private fun renderBatch() {
        binding.batchContainer.removeAllViews()
        binding.tvBatchEmptyState.visibility = if (batch.isEmpty()) View.VISIBLE else View.GONE
        binding.btnExportCsv.isEnabled = batch.isNotEmpty()
        binding.btnExportXlsx.isEnabled = batch.isNotEmpty()
        for ((index, invoice) in batch.withIndex()) {
            binding.batchContainer.addView(buildBatchRow(invoice, index))
        }
    }

    private fun buildBatchRow(invoice: ParsedInvoice, index: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = ContextCompat.getDrawable(this@InvoiceOcrActivity, R.drawable.bg_input_field)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        }
        val label = listOfNotNull(
            invoice.company.ifBlank { null },
            invoice.invoiceNumber.ifBlank { null },
            invoice.customerName.ifBlank { null },
            invoice.total.ifBlank { null }?.let { "$$it" }
        ).joinToString("  •  ").ifBlank { invoice.sourceLabel.ifBlank { "Invoice ${index + 1}" } }
        val icon = if (invoice.itemsNeedReview) "⚠️" else "🧾"

        row.addView(TextView(this).apply {
            text = "$icon  $label"
            setTextColor(colorOf(R.color.text_primary))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = "🗑"
            setTextColor(colorOf(R.color.error))
            textSize = 15f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            isFocusable = true
            val outValue = TypedValue()
            this@InvoiceOcrActivity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setOnClickListener {
                batch.removeAt(index)
                renderBatch()
            }
        })
        return row
    }

    private fun exportTo(uri: Uri, asXlsx: Boolean) {
        if (batch.isEmpty()) {
            Toast.makeText(this, "Nothing in the batch to export", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val rows = buildExportRows()
                withContext(Dispatchers.IO) {
                    val temp = File.createTempFile("invoices_", if (asXlsx) ".xlsx" else ".csv", cacheDir)
                    if (asXlsx) ExcelCsvHelper.writeXlsx(rows, temp, sheetName = "Invoices") else ExcelCsvHelper.writeCsv(rows, temp)
                    contentResolver.openOutputStream(uri)?.use { out ->
                        temp.inputStream().use { input -> input.copyTo(out) }
                    } ?: throw IllegalStateException("Could not open destination for writing")
                    temp.delete()
                }
                Toast.makeText(this@InvoiceOcrActivity, "Exported ${batch.size} invoice(s)", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@InvoiceOcrActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * No fixed column list: the header is the union of whatever fields were actually
     * detected across this batch, so a stack of mixed formats (a GST retail bill next to a
     * plain freelance invoice) produces one clean sheet with every field each one
     * contributed, and blanks wherever a particular receipt didn't have that field. Core
     * fields come first in a stable order when present, followed by whatever extra fields
     * were detected, in the order they first appeared in the batch.
     *
     * Each line item gets its own row - Item / Quantity / Unit Price / Amount as real
     * columns, not one comma-separated cell - with the invoice-level fields repeated on
     * every one of that invoice's rows so the sheet stays sortable/filterable by item
     * (e.g. "everything above ₹500") the way a flat items register normally works. An
     * invoice with no line items detected still gets one row, item columns left blank.
     * "Totals Check" is ReceiptTableDetector's own row-alignment/subtotal cross-check for
     * that invoice, repeated per row the same way - blank when there was nothing to check.
     */
    private fun buildExportRows(): List<List<String>> {
        val presentCore = CoreInvoiceFields.ORDERED.filter { key -> batch.any { it.fields.containsKey(key) } }
        val extraColumns = LinkedHashSet<String>()
        for (invoice in batch) extraColumns.addAll(invoice.extraFields.keys)

        val fieldColumns = presentCore + extraColumns
        val header = fieldColumns + listOf("Item", "Quantity", "Unit Price", "Amount", "Source File", "Totals Check")
        val rows = mutableListOf<List<String>>(header)

        for (invoice in batch) {
            val invoiceValues = fieldColumns.map { column -> invoice.fields[column].orEmpty() }
            if (invoice.items.isEmpty()) {
                rows.add(invoiceValues + listOf("", "", "", "", invoice.sourceLabel, invoice.itemsValidationNote))
            } else {
                for (item in invoice.items) {
                    rows.add(invoiceValues + listOf(item.description, item.quantity, item.unitPrice, item.amount, invoice.sourceLabel, invoice.itemsValidationNote))
                }
            }
        }
        return rows
    }

    // ---- Card 4: recent activity -------------------------------------------------------------

    private fun renderHistory() {
        lifecycleScope.launch {
            val recent = withContext(Dispatchers.IO) { db.processedItemDao().recentByFeature("INVOICE_OCR", 10) }
            binding.historyContainer.removeAllViews()
            binding.tvHistoryEmptyState.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
            for (item in recent) {
                binding.historyContainer.addView(buildHistoryRow(item))
            }
        }
    }

    private fun buildHistoryRow(item: ProcessedItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = ContextCompat.getDrawable(this@InvoiceOcrActivity, R.drawable.bg_input_field)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        headerRow.addView(TextView(this).apply {
            text = item.inputLabel
            setTextColor(colorOf(R.color.text_primary))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(TextView(this).apply {
            text = item.status
            setTextColor(colorOf(if (item.status == "SUCCESS") R.color.success else R.color.error))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 10.5f
        })
        row.addView(headerRow)
        row.addView(TextView(this).apply {
            text = "${dateLabel(item.timestamp)}  •  quality ${item.qualityScore}%  •  ${formatDuration(item.durationMs)}"
            setTextColor(colorOf(R.color.text_secondary))
            textSize = 11f
            setPadding(0, dp(2), 0, 0)
        })
        return row
    }

    // ---- Small shared helpers ------------------------------------------------------------------

    private suspend fun recordScan(inputKey: String, label: String, previewText: String, quality: Int, status: String, durationMs: Long) {
        cache.record(
            feature = "INVOICE_OCR",
            inputText = inputKey,
            inputLabel = label,
            outputPreview = previewText.take(300),
            outputPath = null,
            qualityScore = quality,
            status = status,
            durationMs = durationMs
        )
    }

    private fun displayNameOf(uri: Uri): String {
        var name = uri.lastPathSegment ?: "invoice"
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.let { name = it }
                }
            }
        } catch (_: Exception) {
            // Fall back to the lastPathSegment already captured above.
        }
        return name
    }

    private fun setBusy(busy: Boolean) {
        binding.btnCamera.isEnabled = !busy
        binding.btnGallery.isEnabled = !busy
        binding.btnBatchPick.isEnabled = !busy
        if (busy) binding.btnAddToBatch.isEnabled = false
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun colorOf(resId: Int) = ContextCompat.getColor(this, resId)
    private fun formatDuration(ms: Long): String = if (ms < 1000) "${ms}ms" else "%.1fs".format(ms / 1000.0)
    private fun dateLabel(millis: Long): String = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(millis))
}
