package com.prateek.datatoolkit.features.datacleaning

import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.prateek.datatoolkit.core.cache.CacheManager
import com.prateek.datatoolkit.core.quality.QualityScorer
import com.prateek.datatoolkit.databinding.ActivityDataCleaningBinding
import kotlinx.coroutines.launch
import java.io.File

class DataCleaningActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDataCleaningBinding
    private lateinit var cache: CacheManager
    private var lastOutputRows: List<List<String>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDataCleaningBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cache = CacheManager(this)

        binding.btnClean.setOnClickListener { runCleaning() }
        binding.btnSave.setOnClickListener { saveOutput() }
    }

    private fun runCleaning() {
        val inputText = binding.etInput.text.toString()
        if (inputText.isBlank()) {
            Toast.makeText(this, "Paste some rows first", Toast.LENGTH_SHORT).show()
            return
        }

        val options = CleaningOptions(
            trimWhitespace = binding.cbTrim.isChecked,
            collapseInnerSpaces = binding.cbCollapse.isChecked,
            removeDuplicateRows = binding.cbDedupe.isChecked,
            removeEmptyRows = binding.cbEmptyRows.isChecked
        )

        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            val rows = DataCleaner.parseCsvText(inputText)
            val (cleaned, report) = DataCleaner.clean(rows, options)
            lastOutputRows = cleaned
            val outputText = DataCleaner.toCsvText(cleaned)
            binding.etOutput.setText(outputText)

            val quality = QualityScorer.scoreTable(cleaned)
            binding.tvReport.text = "Rows: ${report.rowsIn} → ${report.rowsOut}  |  " +
                "Duplicates removed: ${report.duplicatesRemoved}  |  " +
                "Empty rows removed: ${report.emptyRowsRemoved}  |  " +
                "Quality: $quality/100 (${QualityScorer.label(quality)})"

            cache.record(
                feature = "CLEANING",
                inputText = inputText,
                inputLabel = "Pasted data (${report.rowsIn} rows)",
                outputPreview = outputText,
                outputPath = null,
                qualityScore = quality,
                status = "SUCCESS",
                durationMs = System.currentTimeMillis() - start
            )
        }
    }

    private fun saveOutput() {
        val text = binding.etOutput.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "Nothing to save yet", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        val file = File(dir, "cleaned_${System.currentTimeMillis()}.csv")
        file.writeText(text)
        Toast.makeText(this, "Saved to ${file.absolutePath}", Toast.LENGTH_LONG).show()
    }
}
