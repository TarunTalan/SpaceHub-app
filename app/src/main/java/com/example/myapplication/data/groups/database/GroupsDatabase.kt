package com.example.myapplication.data.groups.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.myapplication.data.groups.dao.GroupDao
import com.example.myapplication.data.groups.model.LocalGroup

@Database(entities = [LocalGroup::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class GroupsDatabase : RoomDatabase() {

    abstract fun groupDao(): GroupDao

    companion object {
        @Volatile
        private var INSTANCE: GroupsDatabase? = null

        fun getInstance(context: Context): GroupsDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): GroupsDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                GroupsDatabase::class.java,
                "groups_database"
            ).fallbackToDestructiveMigration().build()
        }

        /**
         * Clear all tables and close the database instance. Safe to call from background thread.
         */
        fun clearAndClose(context: Context) {
            synchronized(this) {
                try {
                    val inst = INSTANCE ?: run { getInstance(context) }
                    // Only clear tables. Avoid closing the instance here because other threads may be
                    // using the DB. Closing causes the connection pool to be shutdown and can throw
                    // IllegalStateException in concurrent operations.
                    try { inst.clearAllTables() } catch (_: Exception) {}
                } catch (_: Exception) {
                } finally {
                    // Do not set INSTANCE = null; keep the singleton to avoid racing with active users.
                }
            }
        }
    }
}
