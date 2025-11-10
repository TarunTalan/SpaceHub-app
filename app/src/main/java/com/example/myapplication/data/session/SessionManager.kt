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

            // NOTE: Do NOT clear or close Room databases immediately from here. Clearing tables while other
            // coroutines or threads are using Room can cause SQLite "database is locked" or
            // "connection pool has been closed" errors. To avoid races, mark DB files for deletion on next
            // app restart and perform any destructive DB file operations when the process is not actively
            // using the DB (for example, in Activity.onCreate on next cold start).
            // The MainActivity already checks the `delete_db_on_restart` flag at startup and will delete
            // the DB files before any DB instances are created.

            try {
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("delete_db_on_restart", true).apply()
            } catch (_: Exception) {}

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
