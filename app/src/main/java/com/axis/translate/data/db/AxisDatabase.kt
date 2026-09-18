package com.axis.translate.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Single Room database for history, favorites and glossary (schema v1,
 * destructive fallback — all tables are derived from user-visible data).
 */
@Database(
    entities = [
        HistoryEntity::class,
        FavoriteEntity::class,
        GlossaryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AxisDatabase : RoomDatabase() {

    abstract fun historyDao(): HistoryDao

    abstract fun favoriteDao(): FavoriteDao

    abstract fun glossaryDao(): GlossaryDao

    companion object {
        private const val DATABASE_NAME = "axis.db"

        @Volatile
        private var INSTANCE: AxisDatabase? = null

        fun get(context: Context): AxisDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AxisDatabase::class.java,
                DATABASE_NAME
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { database -> INSTANCE = database }
        }
    }
}
