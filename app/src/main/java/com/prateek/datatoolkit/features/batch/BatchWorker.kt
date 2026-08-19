package com.prateek.datatoolkit.features.batch

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.prateek.datatoolkit.core.cache.CacheManager
import com.prateek.datatoolkit.core.network.RetryPolicy
import com.prateek.datatoolkit.core.quality.QualityScorer
import com.prateek.datatoolkit.core.storage.OutputStorage
import com.prateek.datatoolkit.features.ocr.OcrHelper
import com.prateek.datatoolkit.features.pdf.PdfHelper
import java.io.File
import java.io.FileOutputStream

/**
 * Batch Processing: runs a queued set of files (images -> OCR, or PDFs ->
 * text extraction) in the background via WorkManager. Each item gets its
 * own Auto-Retry (RetryPolicy) so one bad file doesn't sink the whole batch;
 * WorkManager's own backoff (set on the WorkRequest) retries the *entire*
 * run only if too many individual items failed.
 *
 * Each successfully processed item's full text is also auto-saved into
 * Downloads/Output/Batch/ (auto-created, collision-proof name) - see
 * [outputFileName]/doWork. A storage hiccup there doesn't fail the item or
 * spend one of its OCR/PDF-extraction retries: it's a separate concern from
 * whether recognition/extraction itself succeeded.
 */
class BatchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_TYPE = "type"          // "OCR" or "PDF_TEXT"
        const val KEY_URIS = "uris"           // String array of content URIs
        const val KEY_PROGRESS_DONE = "done"
        const val KEY_PROGRESS_TOTAL = "total"
        const val TYPE_OCR = "OCR"
        const val TYPE_PDF_TEXT = "PDF_TEXT"
        /** If more than this fraction of items fail, ask WorkManager to retry the whole batch. */
        const val FAILURE_RATIO_FOR_RETRY = 0.5
    }

    private val cache = CacheManager(applicationContext)

    override suspend fun doWork(): Result {
        val type = inputData.getString(KEY_TYPE) ?: TYPE_OCR
        val uriStrings = inputData.getStringArray(KEY_URIS) ?: emptyArray()
        val total = uriStrings.size
        var done = 0
        var succeeded = 0
        var failed = 0

        for (uriString in uriStrings) {
            val uri = Uri.parse(uriString)
            val itemResult = RetryPolicy.withRetry(maxAttempts = 3) {
                processOne(type, uri)
            }

            if (itemResult.value != null) {
                succeeded++
                val (label, fullText, score) = itemResult.value
                // A separate try/catch from the OCR/PDF-extraction RetryPolicy above:
                // processing already succeeded at this point, so a storage hiccup here
                // shouldn't discard that result or count against it - it just means
                // outputPath stays null, same as any other item this code hasn't gotten
                // around to saving.
                val outputPath = try {
                    OutputStorage.saveBytes(
                        applicationContext, OutputStorage.Module.BATCH,
                        fullText.toByteArray(), outputFileName(uri, type), "text/plain"
                    ).humanPath
                } catch (e: Exception) {
                    null
                }
                cache.record(
                    feature = "BATCH_$type",
                    inputBytes = uriString.toByteArray(),
                    inputLabel = label,
                    outputPreview = fullText.take(200),
                    outputPath = outputPath,
                    qualityScore = score,
                    status = "SUCCESS",
                    retryCount = itemResult.attempts - 1
                )
            } else {
                failed++
                cache.record(
                    feature = "BATCH_$type",
                    inputBytes = uriString.toByteArray(),
                    inputLabel = uriString,
                    outputPreview = "Failed: ${itemResult.lastError?.message}",
                    outputPath = null,
                    qualityScore = 0,
                    status = "FAILED",
                    retryCount = itemResult.attempts - 1
                )
            }

            done++
            setProgressAsync(
                Data.Builder()
                    .putInt(KEY_PROGRESS_DONE, done)
                    .putInt(KEY_PROGRESS_TOTAL, total)
                    .build()
            )
        }

        if (total > 0 && failed.toDouble() / total > FAILURE_RATIO_FOR_RETRY && runAttemptCount < 3) {
            return Result.retry()
        }
        return Result.success(
            Data.Builder()
                .putInt("succeeded", succeeded)
                .putInt("failed", failed)
                .build()
        )
    }

    /** Returns Triple(label, fullText, qualityScore) for one processed item. The caller in
     *  [doWork] both truncates this for the cache preview and saves it in full to
     *  Downloads/Output/Batch/. */
    private suspend fun processOne(type: String, uri: Uri): Triple<String, String, Int> {
        val resolver = applicationContext.contentResolver
        val label = uri.lastPathSegment ?: uri.toString()

        return when (type) {
            TYPE_OCR -> {
                val input = resolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open $uri")
                val bitmap = input.use { BitmapFactory.decodeStream(it) }
                    ?: throw IllegalStateException("Not a valid image: $uri")
                // processOne is itself a suspend function (called from RetryPolicy's suspend
                // lambda), so this can just await OcrHelper.recognize directly - no need to
                // block a WorkManager coroutine-dispatcher thread with runBlocking to call a
                // function that already knows how to suspend.
                val result = OcrHelper.recognize(bitmap)
                val score = QualityScorer.scoreText(result.text)
                Triple(label, result.text, score)
            }
            TYPE_PDF_TEXT -> {
                val tempFile = File.createTempFile("batch_pdf_", ".pdf", applicationContext.cacheDir)
                resolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Cannot open $uri")
                val text = PdfHelper.extractText(tempFile)
                tempFile.delete()
                val score = QualityScorer.scoreText(text)
                Triple(label, text, score)
            }
            else -> throw IllegalArgumentException("Unknown batch type: $type")
        }
    }

    /** "item_1755400000_0_ocr.txt"-style name for the saved output, derived from the local
     *  temp uri BatchProcessingActivity created for this item. */
    private fun outputFileName(uri: Uri, type: String): String {
        val base = uri.path?.let { File(it).nameWithoutExtension }.takeUnless { it.isNullOrBlank() } ?: "batch_item"
        val suffix = if (type == TYPE_OCR) "ocr" else "pdf_text"
        return "${base}_$suffix.txt"
    }
}
