package com.prateek.datatoolkit.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
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
import com.prateek.datatoolkit.core.cache.ProcessedItem
import com.prateek.datatoolkit.core.quality.QualityScorer
import com.prateek.datatoolkit.databinding.ActivityDashboardBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val dao = AppDatabase.get(applicationContext).processedItemDao()
            val total = withContext(Dispatchers.IO) { dao.totalCount() }
            val success = withContext(Dispatchers.IO) { dao.successCount() }
            val failed = withContext(Dispatchers.IO) { dao.failedCount() }
            val avgQuality = withContext(Dispatchers.IO) { dao.averageQuality() } ?: 0.0
            val byFeature = withContext(Dispatchers.IO) { dao.countsByFeature() }
            val recent = withContext(Dispatchers.IO) { dao.recent(15) }

            val successRate = if (total > 0) (success.toDouble() / total * 100) else 0.0

            // ---- Stat strip ----
            binding.tvTotalJobs.text = total.toString()
            binding.tvSuccessRate.text = "${successRate.toInt()}%"
            binding.tvSuccessRate.setTextColor(colorOf(rateColor(successRate.toInt())))
            binding.tvAvgQuality.text = avgQuality.toInt().toString()
            binding.tvAvgQuality.setTextColor(colorOf(rateColor(avgQuality.toInt())))
            binding.tvBreakdown.text = if (total == 0) "Nothing processed yet"
                else "$success succeeded  •  $failed failed  •  avg quality ${"%.0f".format(avgQuality)}/100 (${QualityScorer.label(avgQuality.toInt())})"

            // ---- Chart: cleared explicitly on every load so a "Clear history" run doesn't
            // leave the previous run's stale bars on screen once there's no data to show. ----
            if (byFeature.isNotEmpty()) {
                val entries = byFeature.mapIndexed { index, fc -> BarEntry(index.toFloat(), fc.count.toFloat()) }
                val dataSet = BarDataSet(entries, "Jobs processed")
                dataSet.color = colorOf(R.color.primary)
                dataSet.valueTextSize = 11f
                dataSet.valueTextColor = colorOf(R.color.text_primary)

                binding.barChart.apply {
                    visibility = View.VISIBLE
                    data = BarData(dataSet)
                    description.isEnabled = false
                    legend.isEnabled = false
                    setDrawGridBackground(false)
                    xAxis.position = XAxis.XAxisPosition.BOTTOM
                    xAxis.granularity = 1f
                    xAxis.valueFormatter = IndexAxisValueFormatter(byFeature.map { it.feature })
                    xAxis.textColor = colorOf(R.color.text_secondary)
                    xAxis.gridColor = colorOf(R.color.stroke)
                    xAxis.axisLineColor = colorOf(R.color.stroke)
                    axisLeft.textColor = colorOf(R.color.text_secondary)
                    axisLeft.gridColor = colorOf(R.color.stroke)
                    axisLeft.axisLineColor = colorOf(R.color.stroke)
                    axisRight.isEnabled = false
                    setFitBars(true)
                    invalidate()
                }
                binding.tvChartEmptyState.visibility = View.GONE
            } else {
                binding.barChart.clear()
                binding.barChart.visibility = View.GONE
                binding.tvChartEmptyState.visibility = View.VISIBLE
            }

            // ---- Recent activity ----
            binding.recentContainer.removeAllViews()
            binding.tvRecentEmptyState.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
            for (item in recent) {
                binding.recentContainer.addView(buildRecentRow(item))
            }

            binding.progressBar.visibility = View.GONE
        }
    }

    private fun buildRecentRow(item: ProcessedItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = ContextCompat.getDrawable(this@DashboardActivity, R.drawable.bg_input_field)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        headerRow.addView(TextView(this).apply {
            text = "${item.feature}  ${item.inputLabel.take(30)}"
            setTextColor(colorOf(R.color.text_primary))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(TextView(this).apply {
            text = item.status
            setTextColor(colorOf(if (item.status == "SUCCESS") R.color.success else if (item.status == "FAILED") R.color.error else R.color.accent))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 10.5f
            setPadding(dp(8), 0, 0, 0)
        })
        row.addView(headerRow)
        row.addView(TextView(this).apply {
            text = "${dateLabel(item.timestamp)}  •  quality ${item.qualityScore}/100"
            setTextColor(colorOf(R.color.text_secondary))
            textSize = 11f
            setPadding(0, dp(2), 0, 0)
        })
        return row
    }

    /** Green/amber/red by the same thresholds QualityScorer.label() uses, so a glance at a
     *  number's color already hints whether it's "Good" or "Poor" before reading the label. */
    private fun rateColor(score: Int): Int = when {
        score >= 65 -> R.color.success
        score >= 40 -> R.color.accent
        else -> R.color.error
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun colorOf(resId: Int) = ContextCompat.getColor(this, resId)
    private fun dateLabel(millis: Long): String =
        SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(millis))
}
