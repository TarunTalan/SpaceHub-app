package com.example.myapplication.data.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.myapplication.data.network.SharedPrefsTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import android.content.Intent

// Use the same name as old SharedPreferences for signup email compatibility
private val Context.authDataStore by preferencesDataStore(name = "auth_prefs")

object SessionManager {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun clearSession(context: Context) {
        try {
            // Clear auth tokens
            runCatching { SharedPrefsTokenStore(context).clear() }

            // Clear DataStore user profile/session data
            runCatching {
                com.example.myapplication.data.user.UserDataManager
                    .getInstance(context)
                    .clear()
            }

            // Perform DB clear/close and related cleanup sequentially on the IO scope to avoid races
            scope.launch {
                try {
                    // 1) Clear all tables on community DB
                    runCatching {
                        com.example.myapplication.data.community.database.CommunityDatabase
                            .getInstance(context)
                            .clearAllTables()
                    }

                    // 2) Clear tables and perform safe close operations (our clearAndClose no longer closes Room instance)
                    try {
                        com.example.myapplication.data.community.database.CommunityDatabase.clearAndClose(context)
                    } catch (_: Exception) {}
                    try {
                        com.example.myapplication.data.groups.database.GroupsDatabase.clearAndClose(context)
                    } catch (_: Exception) {}

                    // 3) Clear chat tables from the community DB (safely collect snapshot)
                    try {
                        val db = com.example.myapplication.data.community.database.CommunityDatabase.getInstance(context)
                        try {
                            val chatDao = db.chatDao()
                            val convs = runCatching { chatDao.getAllConversations().first() }.getOrNull() ?: emptyList()
                            convs.forEach { conv ->
                                runCatching { chatDao.deleteMessagesForConversation(conv.id) }
                                runCatching { chatDao.deleteConversation(conv.id) }
                            }
                        } catch (_: Exception) {}
                    } catch (_: Exception) {}

                    // 4) Mark DB files for deletion on next app restart to avoid deleting active DB while app is running.
                    try {
                        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("delete_db_on_restart", true).apply()
                    } catch (_: Exception) {}
                } catch (_: Exception) {
                    // ignore
                }
            }

            // Clear app-level SharedPreferences used by UI (like last_screen)
            runCatching {
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                prefs.edit().clear().apply()
            }

            // Clear auth DataStore entirely as well
            scope.launch {
                try {
                    context.authDataStore.edit { it.clear() }
                } catch (_: Exception) {}
            }

            // Broadcast an app-wide logout event so in-memory ViewModels / LiveData can clear themselves
            try {
                val intent = Intent("com.example.myapplication.ACTION_LOGOUT")
                intent.setPackage(context.packageName)
                context.sendBroadcast(intent)
            } catch (_: Exception) {}

            // Purge cache directories (temporary media, images, etc.)
            runCatching { context.cacheDir?.deleteRecursively() }
            runCatching { context.externalCacheDir?.deleteRecursively() }

            // Also clear Glide caches (memory on main thread, disk on background)
            runCatching {
                Handler(Looper.getMainLooper()).post {
                    try { com.bumptech.glide.Glide.get(context).clearMemory() } catch (_: Exception) {}
                }
                scope.launch(Dispatchers.IO) {
                    runCatching { com.bumptech.glide.Glide.get(context).clearDiskCache() }
                }
            }

            // NOTE: database files will be deleted on next app restart (marked via shared prefs) to avoid
            // races where other coroutines hold DB connections in the current process.
        } catch (_: Exception) {
        }
    }

    fun clearSignupEmail(context: Context) {
        scope.launch {
            try {
                val emailKey = stringPreferencesKey("email")
                context.authDataStore.edit { prefs ->
                    prefs.remove(emailKey)
                }
            } catch (_: Exception) { }
        }
    }
}
