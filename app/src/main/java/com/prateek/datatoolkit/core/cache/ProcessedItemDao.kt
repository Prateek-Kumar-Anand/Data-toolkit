package com.prateek.datatoolkit.core.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ProcessedItemDao {

    @Insert
    suspend fun insert(item: ProcessedItem): Long

    /** Smart-cache lookup: has this exact input already been processed by this feature? */
    @Query("SELECT * FROM processed_items WHERE feature = :feature AND inputHash = :hash AND status = 'SUCCESS' ORDER BY timestamp DESC LIMIT 1")
    suspend fun findCached(feature: String, hash: String): ProcessedItem?

    @Query("SELECT * FROM processed_items ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int = 100): List<ProcessedItem>

    /** Same as [recent], scoped to one feature - backs Workflow Builder's and Invoice OCR's own in-screen History section. */
    @Query("SELECT * FROM processed_items WHERE feature = :feature ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentByFeature(feature: String, limit: Int = 20): List<ProcessedItem>

    @Query("SELECT * FROM processed_items ORDER BY timestamp DESC LIMIT 5000")
    suspend fun all(): List<ProcessedItem>

    @Query("SELECT COUNT(*) FROM processed_items")
    suspend fun totalCount(): Int

    @Query("SELECT COUNT(*) FROM processed_items WHERE status = 'SUCCESS'")
    suspend fun successCount(): Int

    @Query("SELECT COUNT(*) FROM processed_items WHERE status = 'FAILED'")
    suspend fun failedCount(): Int

    @Query("SELECT AVG(qualityScore) FROM processed_items")
    suspend fun averageQuality(): Double?

    @Query("SELECT feature, COUNT(*) as count FROM processed_items GROUP BY feature")
    suspend fun countsByFeature(): List<FeatureCount>

    @Query("SELECT AVG(durationMs) FROM processed_items WHERE feature = :feature")
    suspend fun averageDuration(feature: String): Double?

    @Query("DELETE FROM processed_items")
    suspend fun clearAll()

    /** Keeps only the newest [keep] rows so the history table can't grow without bound. */
    @Query("DELETE FROM processed_items WHERE id NOT IN (SELECT id FROM processed_items ORDER BY timestamp DESC, id DESC LIMIT :keep)")
    suspend fun pruneToNewest(keep: Int)

    /** Privacy retention: drops history older than [cutoff] (epoch millis). */
    @Query("DELETE FROM processed_items WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

data class FeatureCount(val feature: String, val count: Int)
