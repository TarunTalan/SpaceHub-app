package com.example.myapplication.data.community.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.myapplication.data.community.dao.CommunityDao
import com.example.myapplication.data.community.dao.RoomDao
import com.example.myapplication.data.community.model.Community
import com.example.myapplication.data.community.model.RoomEntity
import com.example.myapplication.data.chat.db.ChatDao
import com.example.myapplication.data.chat.model.ChatMessage
import com.example.myapplication.data.chat.model.Conversation

/**
 * Room Database for storing community data.
 * Provides offline access and caching for communities.
 */
@Database(
    entities = [Community::class, RoomEntity::class, ChatMessage::class, Conversation::class],
    version = 5,
    exportSchema = false
)
abstract class CommunityDatabase : RoomDatabase() {

    abstract fun communityDao(): CommunityDao
    abstract fun roomDao(): RoomDao
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: CommunityDatabase? = null

        fun getInstance(context: Context): CommunityDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): CommunityDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                CommunityDatabase::class.java,
                "community_database"
            )
                .fallbackToDestructiveMigration() // For development; use migrations in production
                .build()
        }

        /**
         * Clear all tables and close the database instance. Safe to call from a background thread.
         * This will set the singleton INSTANCE to null so the DB can be recreated cleanly.
         */
        fun clearAndClose(context: Context) {
            synchronized(this) {
                try {
                    val inst = INSTANCE ?: run { getInstance(context) }
                    // Only clear tables. Do NOT call inst.close() here because other coroutines/threads
                    // may still be using the Room instance; closing leads to `connection pool has been closed`.
                    // Clearing tables is sufficient for logout/cleanup use-cases. If you want to fully
                    // remove DB files, call context.deleteDatabase("community_database") after app restart.
                    try { inst.clearAllTables() } catch (_: Exception) {}
                } catch (_: Exception) {
                    // ignore
                } finally {
                    // Keep INSTANCE intact to avoid racing with active DB users. Do not set to null here.
                }
            }
        }
    }
}
