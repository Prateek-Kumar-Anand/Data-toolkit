package com.prateek.datatoolkit.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.prateek.datatoolkit.R
import com.prateek.datatoolkit.core.cache.AppDatabase
import com.prateek.datatoolkit.databinding.ActivityDashboardBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Analytics Dashboard: reads the shared ProcessedItem history (every feature
 * writes to it via CacheManager) and renders totals, a success/failure
 * breakdown, average quality score, and a per-feature bar chart.
 */
class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClear.setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { AppDatabase.get(applicationContext).processedItemDao().clearAll() }
                loadData()
            }
        }

        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            val dao = AppDatabase.get(applicationContext).processedItemDao()
            val total = withContext(Dispatchers.IO) { dao.totalCount() }
            val success = withContext(Dispatchers.IO) { dao.successCount() }
            val failed = withContext(Dispatchers.IO) { dao.failedCount() }
            val avgQuality = withContext(Dispatchers.IO) { dao.averageQuality() } ?: 0.0
            val byFeature = withContext(Dispatchers.IO) { dao.countsByFeature() }
            val recent = withContext(Dispatchers.IO) { dao.recent(15) }

            val successRate = if (total > 0) (success.toDouble() / total * 100) else 0.0

            binding.tvSummary.text = "Total jobs: $total\n" +
                "Success: $success   Failed: $failed   Success rate: ${"%.1f".format(successRate)}%\n" +
                "Average quality score: ${"%.1f".format(avgQuality)}/100"

            if (byFeature.isNotEmpty()) {
                val entries = byFeature.mapIndexed { index, fc -> BarEntry(index.toFloat(), fc.count.toFloat()) }
                val dataSet = BarDataSet(entries, "Jobs processed")
                dataSet.color = ContextCompat.getColor(this@DashboardActivity, R.color.primary)
                dataSet.valueTextSize = 11f

                binding.barChart.apply {
                    data = BarData(dataSet)
                    description.isEnabled = false
                    legend.isEnabled = false
                    xAxis.position = XAxis.XAxisPosition.BOTTOM
                    xAxis.granularity = 1f
                    xAxis.valueFormatter = IndexAxisValueFormatter(byFeature.map { it.feature })
                    axisRight.isEnabled = false
                    setFitBars(true)
                    invalidate()
                }
            }

            binding.tvRecent.text = if (recent.isEmpty()) "No activity yet — try any tool from the home screen."
            else recent.joinToString("\n") { item ->
                "[${item.feature}] ${item.inputLabel.take(30)} — ${item.status}, quality ${item.qualityScore}"
            }
        }
    }
}
