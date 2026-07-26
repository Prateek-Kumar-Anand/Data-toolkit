package com.prateek.datatoolkit.features.excel

import android.net.Uri
import android.os.Bundle
import android.os.Environment
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

    private val pickXlsx = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadXlsx(it) }
    }
    private val pickCsv = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadCsv(it) }
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

    private fun outputDir(): File = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir

    private fun loadXlsx(uri: Uri) {
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
            } catch (e: Exception) {
                binding.tvStatus.text = "Failed to read xlsx: ${e.message}"
            }
        }
    }

    private fun loadCsv(uri: Uri) {
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
        lifecycleScope.launch {
            try {
                val outFile = if (asXlsx)
                    File(outputDir(), "export_${System.currentTimeMillis()}.xlsx")
                else
                    File(outputDir(), "export_${System.currentTimeMillis()}.csv")

                withContext(Dispatchers.IO) {
                    if (asXlsx) ExcelCsvHelper.writeXlsx(currentRows, outFile)
                    else ExcelCsvHelper.writeCsv(currentRows, outFile)
                }
                Toast.makeText(this@ExcelCsvActivity, "Saved to ${outFile.absolutePath}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@ExcelCsvActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
