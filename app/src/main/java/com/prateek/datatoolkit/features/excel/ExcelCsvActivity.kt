package com.prateek.datatoolkit.features.excel

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.prateek.datatoolkit.core.cache.CacheManager
import com.prateek.datatoolkit.core.quality.QualityScorer
import com.prateek.datatoolkit.databinding.ActivityExcelCsvBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ExcelCsvActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExcelCsvBinding
    private lateinit var cache: CacheManager
    private var currentRows: List<List<String>> = emptyList()

    // Whichever export just finished writing to a temp file, waiting for the
    // user to pick its final home via the system "Save As" picker.
    private var pendingExportFile: File? = null

    private val pickXlsx = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadXlsx(it) }
    }
    private val pickCsv = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadCsv(it) }
    }

    private val saveCsvAs = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { copyPendingExportTo(it) }
    }
    private val saveXlsxAs = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        uri?.let { copyPendingExportTo(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExcelCsvBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cache = CacheManager(this)

        binding.btnPickXlsx.setOnClickListener {
            pickXlsx.launch("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        }
        binding.btnPickCsv.setOnClickListener { pickCsv.launch("text/*") }
        binding.btnExportCsv.setOnClickListener { export(asXlsx = false) }
        binding.btnExportXlsx.setOnClickListener { export(asXlsx = true) }
    }

    private fun loadXlsx(uri: Uri) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "Reading spreadsheet..."
        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            try {
                val rows = withContext(Dispatchers.IO) {
                    val file = File.createTempFile("in_", ".xlsx", cacheDir)
                    contentResolver.openInputStream(uri)?.use { i -> FileOutputStream(file).use { o -> i.copyTo(o) } }
                    val r = ExcelCsvHelper.readXlsx(file)
                    file.delete()
                    r
                }
                onRowsLoaded(rows, uri.lastPathSegment ?: "sheet.xlsx", start)
            } catch (e: Throwable) {
                // Throwable, not just Exception: a bad/corrupt spreadsheet can surface as a
                // java.lang.Error (e.g. a StAX factory error) rather than a normal Exception,
                // which would otherwise crash the whole app instead of showing this message.
                binding.tvStatus.text = "Failed to read xlsx: ${e.message}"
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun loadCsv(uri: Uri) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "Reading CSV..."
        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            try {
                val rows = withContext(Dispatchers.IO) {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                    com.prateek.datatoolkit.features.datacleaning.DataCleaner.parseCsvText(text)
                }
                onRowsLoaded(rows, uri.lastPathSegment ?: "data.csv", start)
            } catch (e: Exception) {
                binding.tvStatus.text = "Failed to read CSV: ${e.message}"
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun onRowsLoaded(rows: List<List<String>>, label: String, start: Long) {
        currentRows = rows
        val preview = rows.take(15).joinToString("\n") { it.joinToString(" | ") }
        binding.tvPreview.text = if (rows.size > 15) "$preview\n... (${rows.size} rows total)" else preview
        val quality = QualityScorer.scoreTable(rows)
        binding.tvStatus.text = "Loaded ${rows.size} rows, ${rows.firstOrNull()?.size ?: 0} columns  |  Quality: $quality/100"

        lifecycleScope.launch {
            cache.record(
                feature = "EXCEL_CSV",
                inputText = label + rows.size,
                inputLabel = label,
                outputPreview = preview,
                outputPath = null,
                qualityScore = quality,
                status = "SUCCESS",
                durationMs = System.currentTimeMillis() - start
            )
        }
    }

    private fun export(asXlsx: Boolean) {
        if (currentRows.isEmpty()) {
            Toast.makeText(this, "Open a file first", Toast.LENGTH_SHORT).show()
            return
        }
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "Exporting..."
        lifecycleScope.launch {
            try {
                val tempFile = if (asXlsx)
                    File(cacheDir, "export_${System.currentTimeMillis()}.xlsx")
                else
                    File(cacheDir, "export_${System.currentTimeMillis()}.csv")

                withContext(Dispatchers.IO) {
                    if (asXlsx) ExcelCsvHelper.writeXlsx(currentRows, tempFile)
                    else ExcelCsvHelper.writeCsv(currentRows, tempFile)
                }
                pendingExportFile = tempFile
                // Browse-to-save: let the user pick exactly where this file ends up.
                if (asXlsx) saveXlsxAs.launch(tempFile.name) else saveCsvAs.launch(tempFile.name)
            } catch (e: Exception) {
                Toast.makeText(this@ExcelCsvActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun copyPendingExportTo(uri: Uri) {
        val tempFile = pendingExportFile
        if (tempFile == null || !tempFile.exists()) {
            Toast.makeText(this, "Nothing to save yet", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        tempFile.inputStream().use { input -> input.copyTo(out) }
                    } ?: throw IllegalStateException("Could not open destination for writing")
                }
                Toast.makeText(this@ExcelCsvActivity, "Saved", Toast.LENGTH_LONG).show()
                tempFile.delete()
                pendingExportFile = null
            } catch (e: Exception) {
                Toast.makeText(this@ExcelCsvActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
