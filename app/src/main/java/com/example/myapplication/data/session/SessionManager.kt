package com.example.myapplication.data.session

import android.content.Context
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
            try { SharedPrefsTokenStore(context).clear() } catch (_: Exception) { }

            // Use UserDataManager to centrally clear all user data
            try {
                com.example.myapplication.data.user.UserDataManager
                    .getInstance(context)
                    .clear()
            } catch (_: Exception) { }
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
