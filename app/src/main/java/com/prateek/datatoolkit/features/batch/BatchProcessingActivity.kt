package com.prateek.datatoolkit.features.batch

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.prateek.datatoolkit.core.cache.AppDatabase
import com.prateek.datatoolkit.databinding.ActivityBatchProcessingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class BatchProcessingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBatchProcessingBinding
    private var pendingType: String = BatchWorker.TYPE_OCR

    private val pickImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) startBatch(uris, BatchWorker.TYPE_OCR)
    }
    private val pickPdfs = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) startBatch(uris, BatchWorker.TYPE_PDF_TEXT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatchProcessingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBatchOcr.setOnClickListener { pickImages.launch("image/*") }
        binding.btnBatchPdf.setOnClickListener { pickPdfs.launch("application/pdf") }
    }

    private fun startBatch(uris: List<Uri>, type: String) {
        binding.tvStatus.text = "Preparing ${uris.size} file(s)..."
        lifecycleScope.launch {
            // Copy each picked file into app-private storage so WorkManager can read it
            // reliably later, without depending on the original content:// URI grant.
            val localUris = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "batch_input").apply { mkdirs() }
                uris.mapIndexedNotNull { index, uri ->
                    try {
                        val ext = if (type == BatchWorker.TYPE_OCR) ".img" else ".pdf"
                        val target = File(dir, "item_${System.currentTimeMillis()}_$index$ext")
                        contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(target).use { output -> input.copyTo(output) }
                        }
                        Uri.fromFile(target).toString()
                    } catch (e: Exception) {
                        null
                    }
                }
            }

            if (localUris.isEmpty()) {
                binding.tvStatus.text = "Could not read any of the selected files"
                return@launch
            }

            val request = OneTimeWorkRequestBuilder<BatchWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(BatchWorker.KEY_TYPE, type)
                        .putStringArray(BatchWorker.KEY_URIS, localUris.toTypedArray())
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()

            val wm = WorkManager.getInstance(applicationContext)
            wm.enqueue(request)

            wm.getWorkInfoByIdLiveData(request.id).observe(this@BatchProcessingActivity) { info ->
                if (info == null) return@observe
                val progress = info.progress
                val done = progress.getInt(BatchWorker.KEY_PROGRESS_DONE, 0)
                val total = progress.getInt(BatchWorker.KEY_PROGRESS_TOTAL, localUris.size)
                binding.progressBar.max = total.coerceAtLeast(1)
                binding.progressBar.progress = done

                when (info.state) {
                    WorkInfo.State.RUNNING -> binding.tvStatus.text = "Processing $done / $total..."
                    WorkInfo.State.SUCCEEDED -> {
                        val succeeded = info.outputData.getInt("succeeded", 0)
                        val failed = info.outputData.getInt("failed", 0)
                        binding.tvStatus.text = "Done: $succeeded succeeded, $failed failed"
                        loadLog()
                    }
                    WorkInfo.State.FAILED -> binding.tvStatus.text = "Batch job failed after retries"
                    WorkInfo.State.BLOCKED, WorkInfo.State.ENQUEUED -> binding.tvStatus.text = "Queued..."
                    else -> {}
                }
            }
        }
    }

    private fun loadLog() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                AppDatabase.get(applicationContext).processedItemDao().recent(20)
                    .filter { it.feature.startsWith("BATCH_") }
            }
            binding.tvLog.text = items.joinToString("\n") { item ->
                "[${item.status}] ${item.inputLabel} — quality ${item.qualityScore}, retries ${item.retryCount}"
            }
        }
    }
}
