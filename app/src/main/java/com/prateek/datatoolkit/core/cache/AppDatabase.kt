package com.prateek.datatoolkit.core.cache

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ProcessedItem::class, SavedWorkflow::class], version = 3, exportSchema = false)
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

        /** Adds the lookup indices the smart-cache/dashboard queries filter and sort on (they
         *  previously did a full table scan every time), and drops history rows beyond a sane cap
         *  in the same step so the upgrade also cleans up an already-bloated table. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_processed_items_feature_inputHash` ON `processed_items` (`feature`, `inputHash`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_processed_items_timestamp` ON `processed_items` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_processed_items_feature_timestamp` ON `processed_items` (`feature`, `timestamp`)")
                db.execSQL("DELETE FROM `processed_items` WHERE `id` NOT IN (SELECT `id` FROM `processed_items` ORDER BY `timestamp` DESC, `id` DESC LIMIT 5000)")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "toolkit_cache.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    // A downgrade (older APK over a newer DB) would otherwise crash on every launch.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build().also { INSTANCE = it }
            }
    }
}
