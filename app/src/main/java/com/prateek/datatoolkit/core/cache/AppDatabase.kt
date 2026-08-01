package com.prateek.datatoolkit.core.cache

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ProcessedItem::class, SavedWorkflow::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun processedItemDao(): ProcessedItemDao
    abstract fun savedWorkflowDao(): SavedWorkflowDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /** Adds the saved_workflows table (Workflow Builder's "Save Workflow" feature) - a real
         *  migration, not a destructive one, so upgrading the app never wipes the
         *  processed_items history every other feature's dashboard/history relies on. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_workflows` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `stepsJson` TEXT NOT NULL,
                        `stepCount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `lastRunAt` INTEGER
                    )
                    """.trimIndent()
                )
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "toolkit_cache.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
    }
}
