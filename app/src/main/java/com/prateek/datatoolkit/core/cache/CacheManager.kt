package com.prateek.datatoolkit.core.cache

import android.content.Context
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
    ): Long = dao.insert(
        ProcessedItem(
            feature = feature,
            inputHash = sha256(inputBytes),
            inputLabel = inputLabel,
            outputPreview = outputPreview.take(500),
            outputPath = outputPath,
            qualityScore = qualityScore,
            status = status,
            retryCount = retryCount,
            durationMs = durationMs
        )
    )

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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
