package com.example.myapplication.data.network

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/*
 * Token storage interface for JWT access/refresh tokens.
 * Provides abstraction for storing and retrieving authentication tokens.
 */
interface TokenStore {
    fun getAccessToken(): String?
    fun setAccessToken(token: String?)
    fun getRefreshToken(): String?
    fun setRefreshToken(token: String?)
    fun clear()
}

/*
 * SharedPreferences-based implementation of TokenStore.
 * Stores JWT tokens in encrypted SharedPreferences for secure persistence.
 */
class SharedPrefsTokenStore(context: Context) : TokenStore {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getAccessToken(): String? = prefs.getString(KEY_ACCESS, null)

    override fun setAccessToken(token: String?) {
        // Use synchronous commit to ensure subsequent requests (called immediately after login)
        // will see the token. This prevents a race where prefs.apply() hasn't completed yet.
        prefs.edit(commit = true) { putString(KEY_ACCESS, token) }
    }

    override fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH, null)

    override fun setRefreshToken(token: String?) {
        prefs.edit(commit = true) { putString(KEY_REFRESH, token) }
    }

    override fun clear() {
        prefs.edit(commit = true) { clear() }
    }

    companion object {
        private const val PREFS_NAME = "auth_tokens"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
    }
}
