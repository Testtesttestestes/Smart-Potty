package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Child::class, TrackerEvent::class], version = 7, exportSchema = false)
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

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "baby_tracker_database"
                )
                .addMigrations(MIGRATION_6_7)
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
