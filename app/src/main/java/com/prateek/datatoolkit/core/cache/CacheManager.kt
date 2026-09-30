package com.prateek.datatoolkit.core.cache

import android.content.Context
import com.prateek.datatoolkit.core.security.PrivacyMask
import java.security.MessageDigest

/**
 * Smart Caching: every feature hashes its input (bytes, URL, or text) and
 * checks CacheManager before doing real work. If the same input was already
 * processed, the cached result is returned instantly instead of re-running
 * OCR / scraping / PDF parsing / etc.
 */
class CacheManager(context: Context) {
    private val dao = AppDatabase.get(context).processedItemDao()

    suspend fun findCached(feature: String, inputBytes: ByteArray): ProcessedItem? =
        dao.findCached(feature, sha256(inputBytes))

    suspend fun findCached(feature: String, inputText: String): ProcessedItem? =
        dao.findCached(feature, sha256(inputText.toByteArray()))

    suspend fun record(
        feature: String,
        inputBytes: ByteArray,
        inputLabel: String,
        outputPreview: String,
        outputPath: String?,
        qualityScore: Int,
        status: String,
        retryCount: Int = 0,
        durationMs: Long = 0
    ): Long = try {
        val id = dao.insert(
            ProcessedItem(
                feature = feature.take(40),
                inputHash = sha256(inputBytes),
                inputLabel = PrivacyMask.maskPreview(inputLabel, 200),
                // Previews are only for history lists - personal data (emails, long digit runs
                // such as card numbers) is masked before it's ever written to disk.
                outputPreview = PrivacyMask.maskPreview(outputPreview, 300),
                outputPath = outputPath,
                qualityScore = qualityScore.coerceIn(0, 100),
                status = status,
                retryCount = retryCount,
                durationMs = durationMs
            )
        )
        // Housekeeping every so often: bounded table size + 90-day retention.
        if (id % 25L == 0L) {
            dao.pruneToNewest(MAX_HISTORY_ROWS)
            dao.deleteOlderThan(System.currentTimeMillis() - RETENTION_MS)
        }
        id
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // History is a convenience - a database hiccup must never turn a finished job into a failure.
        -1L
    }

    suspend fun record(
        feature: String,
        inputText: String,
        inputLabel: String,
        outputPreview: String,
        outputPath: String?,
        qualityScore: Int,
        status: String,
        retryCount: Int = 0,
        durationMs: Long = 0
    ): Long = record(feature, inputText.toByteArray(), inputLabel, outputPreview, outputPath, qualityScore, status, retryCount, durationMs)

    companion object {
        const val MAX_HISTORY_ROWS = 2000
        const val RETENTION_MS = 90L * 24 * 60 * 60 * 1000
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
