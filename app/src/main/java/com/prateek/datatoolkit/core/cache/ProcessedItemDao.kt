package com.prateek.datatoolkit.core.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ProcessedItemDao {

    @Insert
    suspend fun insert(item: ProcessedItem): Long

    /** Smart-cache lookup: has this exact input already been processed by this feature? */
    @Query("SELECT * FROM processed_items WHERE feature = :feature AND inputHash = :hash ORDER BY timestamp DESC LIMIT 1")
    suspend fun findCached(feature: String, hash: String): ProcessedItem?

    @Query("SELECT * FROM processed_items ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int = 100): List<ProcessedItem>

    @Query("SELECT * FROM processed_items ORDER BY timestamp DESC")
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
}

data class FeatureCount(val feature: String, val count: Int)
