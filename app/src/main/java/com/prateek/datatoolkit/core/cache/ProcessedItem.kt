package com.prateek.datatoolkit.core.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per processed job across every feature (OCR, PDF, Excel/CSV,
 * scraping, email extraction, batch, cleaning). This table backs both:
 *  - Smart caching: lookups by [inputHash] avoid redoing identical work.
 *  - The Analytics Dashboard: aggregated counts/charts are computed from this table.
 */
@Entity(tableName = "processed_items")
data class ProcessedItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feature: String,          // e.g. "OCR", "PDF", "EXCEL_CSV", "SCRAPING", "EMAIL", "CLEANING", "BATCH"
    val inputHash: String,        // hash of the input (file bytes, URL, or pasted text) - cache key
    val inputLabel: String,       // human readable label (filename / URL / first 40 chars)
    val outputPreview: String,    // short preview of the result, for history lists
    val outputPath: String? = null, // path to the full result file, if one was saved
    val qualityScore: Int,        // 0-100, from QualityScorer
    val status: String,           // "SUCCESS", "FAILED", "RETRIED"
    val retryCount: Int = 0,
    val durationMs: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
)
