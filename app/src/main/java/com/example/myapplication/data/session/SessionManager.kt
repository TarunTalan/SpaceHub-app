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

            // Wipe Room database tables (communities, rooms, etc.)
            scope.launch {
                runCatching {
                    com.example.myapplication.data.community.database.CommunityDatabase
                        .getInstance(context)
                        .clearAllTables()
                }
            }

            // Clear app-level SharedPreferences used by UI (like last_screen)
            runCatching {
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                prefs.edit().clear().apply()
            }

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
