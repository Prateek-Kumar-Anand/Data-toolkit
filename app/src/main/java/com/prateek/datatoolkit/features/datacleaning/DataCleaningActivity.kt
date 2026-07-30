package com.prateek.datatoolkit.features.datacleaning

import android.database.Cursor
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.prateek.datatoolkit.R
import com.prateek.datatoolkit.core.cache.CacheManager
import com.prateek.datatoolkit.core.quality.QualityScorer
import com.prateek.datatoolkit.databinding.ActivityDataCleaningBinding
import com.prateek.datatoolkit.features.excel.ExcelCsvHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** The live controls for one column's per-column rule card (see [DataCleaningActivity.renderColumnRules]). */
private data class ColumnRuleView(
    val columnName: String,
    val skipCheck: CheckBox,
    val caseSpinner: Spinner,
    val typeSpinner: Spinner,
    val fillInput: EditText
)

/** The live controls for one find/replace rule row (see [DataCleaningActivity.addReplaceRuleRow]). */
private data class ReplaceRuleView(
    val findInput: EditText,
    val replaceInput: EditText,
    val regexCheck: CheckBox,
    val ignoreCaseCheck: CheckBox,
    val columnSpinner: Spinner,
    val rowView: View
)

class DataCleaningActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDataCleaningBinding
    private lateinit var cache: CacheManager
    private var lastOutputRows: List<List<String>> = emptyList()

    // Per-column rule cards and find/replace rule rows are built at runtime (we don't know the
    // columns until data is loaded/pasted), so we keep the live view references here to read
    // their state back when "Clean Data" is pressed.
    private val columnRuleViews = mutableListOf<ColumnRuleView>()
    private val replaceRuleViews = mutableListOf<ReplaceRuleView>()
    private var lastHeader: List<String> = emptyList()

    // File browser: accepts CSV/Excel/JSON/TXT - format is detected from the picked
    // file's name (extension) rather than relying on the (often unreliable) content:// mime type.
    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadFile(it) }
    }

    // Browse-to-save: one launcher per export format, each writing the same cleaned
    // table through a different serializer.
    private val saveCsvAs = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { writeExportTo(it, DataFormat.CSV) }
    }
    private val saveJsonAs = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { writeExportTo(it, DataFormat.JSON) }
    }
    private val saveTxtAs = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { writeExportTo(it, DataFormat.TXT) }
    }
    private val saveXlsxAs = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        uri?.let { writeExportTo(it, DataFormat.XLSX) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDataCleaningBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cache = CacheManager(this)

        binding.spinnerCaseMode.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("None", "lower case", "UPPER CASE", "Title Case")
        )

        binding.btnUploadFile.setOnClickListener { pickFile.launch("*/*") }
        binding.btnDetectColumns.setOnClickListener { detectColumns() }
        binding.btnAddReplaceRule.setOnClickListener { addReplaceRuleRow(lastHeader) }
        binding.btnClean.setOnClickListener { runCleaning() }
        binding.btnExportCsv.setOnClickListener { exportAs(saveCsvAs, "cleaned_${System.currentTimeMillis()}.csv") }
        binding.btnExportXlsx.setOnClickListener { exportAs(saveXlsxAs, "cleaned_${System.currentTimeMillis()}.xlsx") }
        binding.btnExportJson.setOnClickListener { exportAs(saveJsonAs, "cleaned_${System.currentTimeMillis()}.json") }
        binding.btnExportTxt.setOnClickListener { exportAs(saveTxtAs, "cleaned_${System.currentTimeMillis()}.txt") }
    }

    private fun loadFile(uri: Uri) {
        binding.progressBar.isIndeterminate = true
        binding.progressBar.visibility = View.VISIBLE
        binding.tvColumnsPreview.text = "Reading file..."
        lifecycleScope.launch {
            try {
                val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "file"
                val format = DataCleaner.formatFromFileName(name)

                val rows = withContext(Dispatchers.IO) {
                    when (format) {
                        DataFormat.XLSX -> {
                            val temp = File.createTempFile("clean_in_", ".xlsx", cacheDir)
                            contentResolver.openInputStream(uri)?.use { i -> FileOutputStream(temp).use { o -> i.copyTo(o) } }
                            val r = ExcelCsvHelper.readXlsx(temp)
                            temp.delete()
                            r
                        }
                        else -> {
                            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
                            DataCleaner.parse(text, format)
                        }
                    }
                }

                if (rows.isEmpty()) {
                    binding.tvColumnsPreview.text = "Could not find any rows in $name"
                } else {
                    // Show the loaded table as editable CSV, same shape the paste box always used -
                    // every downstream step (cleaning, export) works off this one table representation
                    // regardless of which format it came from.
                    binding.etInput.setText(DataCleaner.toCsvText(rows))
                    val header = rows.first()
                    binding.tvColumnsPreview.text = buildString {
                        append("Loaded \"$name\" (${format.name})  •  ${rows.size - 1} rows, ${header.size} columns\n")
                        append("Detected columns: ${header.joinToString(", ")}\n\n")
                        append("Preview:\n")
                        rows.take(6).forEach { append(it.joinToString(" | ")); append("\n") }
                        if (rows.size > 6) append("... (${rows.size - 6} more rows)")
                    }
                    renderColumnRules(header)
                }
            } catch (e: Exception) {
                binding.tvColumnsPreview.text = "Failed to read file: ${e.message}"
                Toast.makeText(this@DataCleaningActivity, "Failed to read file: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.progressBar.isIndeterminate = false
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        } catch (e: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    /** Parses just the header line of whatever is in the paste box and (re)builds the per-column rule cards. */
    private fun detectColumns() {
        val text = binding.etInput.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "Paste or load some data first", Toast.LENGTH_SHORT).show()
            return
        }
        val header = DataCleaner.parseCsvText(text).firstOrNull().orEmpty()
        if (header.isEmpty()) {
            Toast.makeText(this, "Could not detect any columns", Toast.LENGTH_SHORT).show()
            return
        }
        renderColumnRules(header)
        Toast.makeText(this, "${header.size} column(s) detected", Toast.LENGTH_SHORT).show()
    }

    /** Rebuilds the "Per-column rules" cards, one per header name, replacing whatever was there before. */
    private fun renderColumnRules(header: List<String>) {
        lastHeader = header
        binding.columnRulesContainer.removeAllViews()
        columnRuleViews.clear()

        val caseOptions = listOf("Default", "None", "lower", "UPPER", "Title Case")
        val typeOptions = listOf("Auto-detect", "Text (no validation)", "Number", "Email", "Phone", "URL", "Date")

        for (col in header) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 12, 12, 12)
                background = ContextCompat.getDrawable(this@DataCleaningActivity, R.drawable.bg_input_field)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8 }
            }

            val title = TextView(this).apply {
                text = col
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                textSize = 13f
            }
            val skipCheck = CheckBox(this).apply { text = "Skip this column (leave untouched)" }

            val spinnerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val caseSpinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@DataCleaningActivity, android.R.layout.simple_spinner_dropdown_item, caseOptions)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val typeSpinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@DataCleaningActivity, android.R.layout.simple_spinner_dropdown_item, typeOptions)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            spinnerRow.addView(caseSpinner)
            spinnerRow.addView(typeSpinner)

            val fillInput = EditText(this).apply {
                hint = "Fill blanks in this column with (optional)"
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            row.addView(title)
            row.addView(skipCheck)
            row.addView(spinnerRow)
            row.addView(fillInput)
            binding.columnRulesContainer.addView(row)

            columnRuleViews += ColumnRuleView(col, skipCheck, caseSpinner, typeSpinner, fillInput)
        }
    }

    private fun collectColumnRules(): Map<String, ColumnRule> =
        columnRuleViews.associate { v ->
            val case = when (v.caseSpinner.selectedItemPosition) {
                1 -> CaseMode.NONE; 2 -> CaseMode.LOWER; 3 -> CaseMode.UPPER; 4 -> CaseMode.TITLE
                else -> null // "Default" -> fall back to the global setting
            }
            val type = when (v.typeSpinner.selectedItemPosition) {
                1 -> ColumnType.TEXT; 2 -> ColumnType.NUMBER; 3 -> ColumnType.EMAIL
                4 -> ColumnType.PHONE; 5 -> ColumnType.URL; 6 -> ColumnType.DATE
                else -> null // "Auto-detect"
            }
            v.columnName to ColumnRule(
                skip = v.skipCheck.isChecked,
                standardizeCase = case,
                expectedType = type,
                fillMissingWith = v.fillInput.text.toString().ifBlank { null }
            )
        }

    /** Adds one blank find/replace rule row, with a "remove" button that deletes just that row. */
    private fun addReplaceRuleRow(columns: List<String>) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            background = ContextCompat.getDrawable(this@DataCleaningActivity, R.drawable.bg_input_field)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }

        val findInput = EditText(this).apply { hint = "Find text or regex pattern" }
        val replaceInput = EditText(this).apply { hint = "Replace with" }

        val checksRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val regexCheck = CheckBox(this).apply { text = "Regex" }
        val ignoreCaseCheck = CheckBox(this).apply { text = "Ignore case" }
        checksRow.addView(regexCheck)
        checksRow.addView(ignoreCaseCheck)

        val columnSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@DataCleaningActivity, android.R.layout.simple_spinner_dropdown_item, listOf("All columns") + columns)
        }

        val removeBtn = Button(this).apply { text = "✕ Remove this rule"; textSize = 12f }

        row.addView(findInput)
        row.addView(replaceInput)
        row.addView(checksRow)
        row.addView(columnSpinner)
        row.addView(removeBtn)
        binding.replaceRulesContainer.addView(row)

        val view = ReplaceRuleView(findInput, replaceInput, regexCheck, ignoreCaseCheck, columnSpinner, row)
        replaceRuleViews += view
        removeBtn.setOnClickListener {
            binding.replaceRulesContainer.removeView(row)
            replaceRuleViews.remove(view)
        }
    }

    private fun collectReplaceRules(): List<ReplaceRule> =
        replaceRuleViews.mapNotNull { v ->
            val find = v.findInput.text.toString()
            if (find.isEmpty()) return@mapNotNull null
            val colPos = v.columnSpinner.selectedItemPosition
            val colName = if (colPos <= 0) null else v.columnSpinner.selectedItem as? String
            ReplaceRule(
                find = find,
                replaceWith = v.replaceInput.text.toString(),
                isRegex = v.regexCheck.isChecked,
                ignoreCase = v.ignoreCaseCheck.isChecked,
                column = colName
            )
        }

    private fun runCleaning() {
        val inputText = binding.etInput.text.toString()
        if (inputText.isBlank()) {
            Toast.makeText(this, "Paste some rows or upload a file first", Toast.LENGTH_SHORT).show()
            return
        }

        val caseMode = when (binding.spinnerCaseMode.selectedItemPosition) {
            1 -> CaseMode.LOWER; 2 -> CaseMode.UPPER; 3 -> CaseMode.TITLE; else -> CaseMode.NONE
        }
        val dedupeKeys = binding.etDedupeKeys.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val options = CleaningOptions(
            trimWhitespace = binding.cbTrim.isChecked,
            collapseInnerSpaces = binding.cbCollapse.isChecked,
            removeDuplicateRows = binding.cbDedupe.isChecked,
            standardizeCase = caseMode,
            removeEmptyRows = binding.cbEmptyRows.isChecked,
            removeUnwantedChars = binding.cbUnwantedChars.isChecked,
            fillMissingWith = binding.etFillMissing.text.toString().ifBlank { null },
            dedupeKeyColumns = dedupeKeys,
            fuzzyDedupe = binding.cbFuzzyDedupe.isChecked,
            columnRules = collectColumnRules(),
            replaceRules = collectReplaceRules(),
            flagInvalidCells = binding.cbFlagInvalid.isChecked
        )

        binding.progressBar.isIndeterminate = true
        binding.progressBar.visibility = View.VISIBLE
        binding.tvReport.text = "Cleaning..."

        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            try {
                val rows = withContext(Dispatchers.Default) { DataCleaner.parseCsvText(inputText) }
                if (columnRuleViews.isEmpty() && rows.isNotEmpty()) {
                    // Columns were never explicitly detected (pasted text, no file load or Detect
                    // Columns tap) - render them now so per-column rules are ready for next time.
                    renderColumnRules(rows.first())
                }
                val (cleaned, report) = withContext(Dispatchers.Default) { DataCleaner.clean(rows, options) }
                lastOutputRows = cleaned
                val outputText = DataCleaner.toCsvText(cleaned)
                binding.etOutput.setText(outputText)

                val quality = QualityScorer.scoreTable(cleaned)
                binding.tvReport.text = buildString {
                    append("Rows: ${report.rowsIn} → ${report.rowsOut}  |  ")
                    append("Duplicates removed: ${report.duplicatesRemoved}  |  ")
                    append("Empty rows removed: ${report.emptyRowsRemoved}  |  ")
                    append("Blanks filled: ${report.cellsFilled}  |  ")
                    append("Unwanted chars stripped: ${report.unwantedCharsRemoved}  |  ")
                    append("Replacements made: ${report.replacementsMade}  |  ")
                    append("Quality: $quality/100 (${QualityScorer.label(quality)})")
                    if (report.detectedTypes.isNotEmpty()) {
                        append("\n\nDetected column types: ")
                        append(report.detectedTypes.entries.joinToString(", ") { "${it.key}=${it.value}" })
                    }
                    if (report.invalidCells.isNotEmpty()) {
                        append("\n\n⚠ ${report.invalidCells.size} value(s) don't match their column's type:\n")
                        report.invalidCells.take(8).forEach {
                            append("• Row ${it.rowIndex + 1}, ${it.column}: \"${it.value}\" (expected ${it.expectedType})\n")
                        }
                        if (report.invalidCells.size > 8) append("...and ${report.invalidCells.size - 8} more")
                    }
                }

                setExportEnabled(cleaned.isNotEmpty())

                cache.record(
                    feature = "CLEANING",
                    inputText = inputText,
                    inputLabel = "Data (${report.rowsIn} rows)",
                    outputPreview = outputText,
                    outputPath = null,
                    qualityScore = quality,
                    status = "SUCCESS",
                    durationMs = System.currentTimeMillis() - start
                )
            } catch (e: Exception) {
                binding.tvReport.text = "Cleaning failed: ${e.message}"
                Toast.makeText(this@DataCleaningActivity, "Cleaning failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.progressBar.isIndeterminate = false
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun setExportEnabled(enabled: Boolean) {
        binding.btnExportCsv.isEnabled = enabled
        binding.btnExportXlsx.isEnabled = enabled
        binding.btnExportJson.isEnabled = enabled
        binding.btnExportTxt.isEnabled = enabled
    }

    private fun exportAs(launcher: androidx.activity.result.ActivityResultLauncher<String>, name: String) {
        if (lastOutputRows.isEmpty()) {
            Toast.makeText(this, "Clean some data first", Toast.LENGTH_SHORT).show()
            return
        }
        launcher.launch(name)
    }

    private fun writeExportTo(uri: Uri, format: DataFormat) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val temp = File.createTempFile("clean_out_", ".tmp", cacheDir)
                    if (format == DataFormat.XLSX) {
                        ExcelCsvHelper.writeXlsx(lastOutputRows, temp, sheetName = "Cleaned Data")
                    } else {
                        temp.writeText(DataCleaner.serialize(lastOutputRows, format))
                    }
                    contentResolver.openOutputStream(uri)?.use { out ->
                        temp.inputStream().use { input -> input.copyTo(out) }
                    } ?: throw IllegalStateException("Could not open destination for writing")
                    temp.delete()
                }
                Toast.makeText(this@DataCleaningActivity, "Saved", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@DataCleaningActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
