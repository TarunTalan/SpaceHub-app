package com.example.myapplication.data.session

import android.content.Context
import androidx.core.content.edit
import com.example.myapplication.data.network.SharedPrefsTokenStore
import java.io.File

/*
 * Centralized session cleanup helper.
 * Use SessionManager.clearSession(context) to remove persisted tokens, signup data and cached profile image.
 * Use SessionManager.clearSignupEmail(context) to only remove the transient signup email.
 */
object SessionManager {
    private const val APP_PREFS = "app_prefs"

    /*
     * Clear full session data: tokens, signup-related prefs (email/username/uploadedProfileUrl/lockouts)
     * and the cached profile image file. Safe to call on logout.
     */
    fun clearSession(context: Context) {
        try {
            // Clear auth tokens
            try { SharedPrefsTokenStore(context).clear() } catch (_: Exception) { }

            // Clear known app prefs keys
            try {
                val prefs = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
                prefs.edit {
                    remove("email")
                    remove("username")
                    remove("uploaded_profile_url")
                    remove("last_screen")
                    remove("signup_otp_lockout_until")
                    // add other cleanup keys here as needed
                }
            } catch (_: Exception) { }

            // Remove cached profile image file (fixed name used by the app)
            try {
                val cacheFile = File(context.cacheDir, "profile_pic.png")
                if (cacheFile.exists()) cacheFile.delete()
            } catch (_: Exception) { }
        } catch (_: Exception) {
            // swallow to avoid crashing during logout cleanup
        }
    }

    /**
     * Remove only the signup email (used after successful login to avoid stale signup data).
     */
    fun clearSignupEmail(context: Context) {
        try {
            val prefs = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            prefs.edit { remove("email") }
        } catch (_: Exception) { }
    }
}
