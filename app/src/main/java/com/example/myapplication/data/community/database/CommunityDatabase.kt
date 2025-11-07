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
    version = 4,
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
    }
}
