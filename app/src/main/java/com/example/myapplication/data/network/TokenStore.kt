package com.example.myapplication.data.network

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Simple token storage for JWT access/refresh tokens.
 */
interface TokenStore {
    fun getAccessToken(): String?
    fun setAccessToken(token: String?)

    fun getRefreshToken(): String?
    fun setRefreshToken(token: String?)

    fun clear()
}

class SharedPrefsTokenStore(context: Context) : TokenStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getAccessToken(): String? = prefs.getString(KEY_ACCESS, null)

    override fun setAccessToken(token: String?) {
        prefs.edit { putString(KEY_ACCESS, token) }
    }

    override fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH, null)

    override fun setRefreshToken(token: String?) {
        prefs.edit { putString(KEY_REFRESH, token) }
    }

    override fun clear() {
        prefs.edit { clear() }
    }

    companion object {
        private const val PREFS_NAME = "auth_tokens"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
    }
}

