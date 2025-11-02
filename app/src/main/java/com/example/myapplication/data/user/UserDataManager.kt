package com.example.myapplication.data.user

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// DataStore delegate
private val Context.dataStore by preferencesDataStore(name = "user_preferences")

/**
 * Centralized manager for all user profile and community data.
 * Uses Jetpack DataStore (Preferences) and exposes minimal Flows for reactive UI updates.
 */
class UserDataManager private constructor(context: Context) {

    private val dataStore = context.dataStore
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // DataStore Keys
    private object PrefsKeys {
        val PROFILE_IMAGE_URL = stringPreferencesKey("profile_image_url")

        val USERNAME = stringPreferencesKey("username")
        val FIRST_NAME = stringPreferencesKey("first_name")
        val LAST_NAME = stringPreferencesKey("last_name")
        val EMAIL = stringPreferencesKey("email")
        val BIO = stringPreferencesKey("bio")
        val DOB = stringPreferencesKey("dob")
        val LOCATION = stringPreferencesKey("location")
        val WEBSITE = stringPreferencesKey("website")
        val COVER_PHOTO_URL = stringPreferencesKey("cover_photo_url")

        val FOLLOWERS_COUNT = intPreferencesKey("followers_count")
        val FOLLOWING_COUNT = intPreferencesKey("following_count")
        val IS_PRIVATE = booleanPreferencesKey("is_private")
    }

    companion object {
        @Volatile
        private var INSTANCE: UserDataManager? = null

        fun getInstance(context: Context): UserDataManager {
            val appCtx = context.applicationContext
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserDataManager(appCtx).also { INSTANCE = it }
            }
        }
    }

    // Minimal Flow-based reactive data streams used by UI
    val profileImageUrlFlow: Flow<String?> = dataStore.data.map { it[PrefsKeys.PROFILE_IMAGE_URL] }

    val usernameFlow: Flow<String?> = dataStore.data.map { it[PrefsKeys.USERNAME] }
    val firstNameFlow: Flow<String?> = dataStore.data.map { it[PrefsKeys.FIRST_NAME] }
    val lastNameFlow: Flow<String?> = dataStore.data.map { it[PrefsKeys.LAST_NAME] }
    val dateOfBirthFlow: Flow<String?> = dataStore.data.map { it[PrefsKeys.DOB] }
    val bioFlow: Flow<String?> = dataStore.data.map { it[PrefsKeys.BIO] }

    /** Persist primary email in DataStore (source of truth). */
    fun setEmail(email: String?) {
        scope.launch {
            dataStore.edit { prefs ->
                if (!email.isNullOrBlank()) prefs[PrefsKeys.EMAIL] = email else prefs.remove(PrefsKeys.EMAIL)
            }
        }
    }

    /** Read primary email from DataStore (nullable). */
    suspend fun getEmail(): String? {
        return dataStore.data.first()[PrefsKeys.EMAIL]
    }

    /**
     * Update profile image from server response.
     * Only persists remote URL; no local file/uri/resource caching.
     */
    fun updateProfileImage(url: String?) {
        scope.launch {
            dataStore.edit { prefs ->
                if (!url.isNullOrBlank()) {
                    prefs[PrefsKeys.PROFILE_IMAGE_URL] = url
                } else {
                    prefs.remove(PrefsKeys.PROFILE_IMAGE_URL)
                }
            }
        }
    }

    /**
     * Update profile details from API response
     */
    fun updateProfile(
        username: String? = null,
        firstName: String? = null,
        lastName: String? = null,
        email: String? = null,
        bio: String? = null,
        dateOfBirth: String? = null,
        location: String? = null,
        website: String? = null,
        avatarUrl: String? = null,
        coverPhotoUrl: String? = null,
        followersCount: Int? = null,
        followingCount: Int? = null,
        isPrivate: Boolean? = null
    ) {
        scope.launch {
            dataStore.edit { prefs ->
                username?.let { prefs[PrefsKeys.USERNAME] = it }
                firstName?.let { prefs[PrefsKeys.FIRST_NAME] = it }
                lastName?.let { prefs[PrefsKeys.LAST_NAME] = it }
                email?.let { prefs[PrefsKeys.EMAIL] = it }
                bio?.let { prefs[PrefsKeys.BIO] = it }
                dateOfBirth?.let { prefs[PrefsKeys.DOB] = it }
                location?.let { prefs[PrefsKeys.LOCATION] = it }
                website?.let { prefs[PrefsKeys.WEBSITE] = it }

                avatarUrl?.let { prefs[PrefsKeys.PROFILE_IMAGE_URL] = it }
                coverPhotoUrl?.let { prefs[PrefsKeys.COVER_PHOTO_URL] = it }

                followersCount?.let { prefs[PrefsKeys.FOLLOWERS_COUNT] = it }
                followingCount?.let { prefs[PrefsKeys.FOLLOWING_COUNT] = it }
                isPrivate?.let { prefs[PrefsKeys.IS_PRIVATE] = it }
            }
        }
    }

    /**
     * Get the profile image URL if present; otherwise null.
     */
    suspend fun getBestProfileImageSource(): Any? {
        val prefs = dataStore.data.first()
        return prefs[PrefsKeys.PROFILE_IMAGE_URL]?.takeIf { it.isNotBlank() }
    }

    /**
     * Clear all user data (for logout)
     */
    fun clear() {
        scope.launch {
            dataStore.edit { it.clear() }
        }
    }
}
