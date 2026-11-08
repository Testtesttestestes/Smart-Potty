package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Child::class, TrackerEvent::class], version = 8, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun childDao(): ChildDao
    abstract fun trackerEventDao(): TrackerEventDao

    companion object {
        /** v7: drink events (fluidIntakeMl) and model version of stored predictions. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracker_events ADD COLUMN fluidIntakeMl INTEGER")
                db.execSQL("ALTER TABLE tracker_events ADD COLUMN modelVersion INTEGER")
            }
        }

        /** v8: initiator, hold training, latency/uroflow summary, estimated volumes, event source. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracker_events ADD COLUMN initiator TEXT")
                db.execSQL("ALTER TABLE tracker_events ADD COLUMN holdTargetMin INTEGER")
                db.execSQL("ALTER TABLE tracker_events ADD COLUMN latencyMs INTEGER")
                db.execSQL("ALTER TABLE tracker_events ADD COLUMN flowMaxMlPerSec REAL")
                db.execSQL("ALTER TABLE tracker_events ADD COLUMN flowPattern TEXT")
                db.execSQL("ALTER TABLE tracker_events ADD COLUMN volumeEstimated INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracker_events ADD COLUMN source TEXT")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "baby_tracker_database"
                )
                .addMigrations(MIGRATION_6_7, MIGRATION_7_8)
                // v6 → v7 keeps user data. Pre-v6 development schemas had no migrations,
                // so they are still reset; downgrades are reset too.
                .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4, 5)
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
