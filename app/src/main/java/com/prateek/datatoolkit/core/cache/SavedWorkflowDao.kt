package com.prateek.datatoolkit.core.cache

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface SavedWorkflowDao {

    @Insert
    suspend fun insert(workflow: SavedWorkflow): Long

    @Update
    suspend fun update(workflow: SavedWorkflow)

    @Delete
    suspend fun delete(workflow: SavedWorkflow)

    @Query("SELECT * FROM saved_workflows ORDER BY createdAt DESC")
    suspend fun all(): List<SavedWorkflow>

    @Query("SELECT * FROM saved_workflows WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): SavedWorkflow?

    @Query("UPDATE saved_workflows SET lastRunAt = :ranAt WHERE id = :id")
    suspend fun markRun(id: Long, ranAt: Long)
}
