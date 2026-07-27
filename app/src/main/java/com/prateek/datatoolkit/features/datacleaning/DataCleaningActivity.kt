package com.prateek.datatoolkit.features.datacleaning

import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.prateek.datatoolkit.core.cache.CacheManager
import com.prateek.datatoolkit.core.quality.QualityScorer
import com.prateek.datatoolkit.databinding.ActivityDataCleaningBinding
import com.prateek.datatoolkit.features.excel.ExcelCsvHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class DataCleaningActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDataCleaningBinding
    private lateinit var cache: CacheManager
    private var lastOutputRows: List<List<String>> = emptyList()

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

        binding.btnUploadFile.setOnClickListener { pickFile.launch("*/*") }
        binding.btnClean.setOnClickListener { runCleaning() }
        binding.btnExportCsv.setOnClickListener { exportAs(saveCsvAs, "cleaned_${System.currentTimeMillis()}.csv") }
        binding.btnExportXlsx.setOnClickListener { exportAs(saveXlsxAs, "cleaned_${System.currentTimeMillis()}.xlsx") }
        binding.btnExportJson.setOnClickListener { exportAs(saveJsonAs, "cleaned_${System.currentTimeMillis()}.json") }
        binding.btnExportTxt.setOnClickListener { exportAs(saveTxtAs, "cleaned_${System.currentTimeMillis()}.txt") }
    }

    private fun loadFile(uri: Uri) {
        binding.progressBar.isIndeterminate = true
        binding.progressBar.visibility = android.view.View.VISIBLE
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
                }
            } catch (e: Exception) {
                binding.tvColumnsPreview.text = "Failed to read file: ${e.message}"
                Toast.makeText(this@DataCleaningActivity, "Failed to read file: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.progressBar.isIndeterminate = false
                binding.progressBar.visibility = android.view.View.GONE
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

    private fun runCleaning() {
        val inputText = binding.etInput.text.toString()
        if (inputText.isBlank()) {
            Toast.makeText(this, "Paste some rows or upload a file first", Toast.LENGTH_SHORT).show()
            return
        }

        val options = CleaningOptions(
            trimWhitespace = binding.cbTrim.isChecked,
            collapseInnerSpaces = binding.cbCollapse.isChecked,
            removeDuplicateRows = binding.cbDedupe.isChecked,
            removeEmptyRows = binding.cbEmptyRows.isChecked,
            removeUnwantedChars = binding.cbUnwantedChars.isChecked
        )

        binding.progressBar.isIndeterminate = true
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.tvReport.text = "Cleaning..."

        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            try {
                val rows = withContext(Dispatchers.Default) { DataCleaner.parseCsvText(inputText) }
                val (cleaned, report) = withContext(Dispatchers.Default) { DataCleaner.clean(rows, options) }
                lastOutputRows = cleaned
                val outputText = DataCleaner.toCsvText(cleaned)
                binding.etOutput.setText(outputText)

                val quality = QualityScorer.scoreTable(cleaned)
                binding.tvReport.text = "Rows: ${report.rowsIn} → ${report.rowsOut}  |  " +
                    "Duplicates removed: ${report.duplicatesRemoved}  |  " +
                    "Empty rows removed: ${report.emptyRowsRemoved}  |  " +
                    "Unwanted chars stripped: ${report.unwantedCharsRemoved}  |  " +
                    "Quality: $quality/100 (${QualityScorer.label(quality)})"

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
                binding.progressBar.visibility = android.view.View.GONE
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
