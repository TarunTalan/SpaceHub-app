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
        val DEFAULT_ROOMS_CREATED = stringSetPreferencesKey("default_rooms_created")
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

    /*
     * Update profile image from server response.

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
            // Log what we're about to write for easier debugging
            try {
                android.util.Log.d("UserDataManager", "updateProfile: username=$username firstName=$firstName lastName=$lastName email=$email avatarUrl=$avatarUrl")
            } catch (_: Exception) {}

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
     * Synchronous update variant that can be called from suspend contexts to ensure DataStore
     * is updated before the caller continues. Useful for repository flows that want immediacy.
     */
    suspend fun updateProfileBlocking(
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

    /**
     * Compose a stable key for a default room marker.
     * Format: "communityId|roomId|kind" where kind is "chat" or "voice".
     */
    private fun defaultRoomKey(communityId: String, roomId: String, kind: String) = "${communityId}|${roomId}|${kind}"

    /**
     * Check whether a default room marker exists (suspending).
     */
    suspend fun isDefaultRoomCreated(communityId: String, roomId: String, kind: String): Boolean {
        val key = defaultRoomKey(communityId, roomId, kind)
        val prefs = dataStore.data.first()
        val set = prefs[PrefsKeys.DEFAULT_ROOMS_CREATED] ?: emptySet()
        return set.contains(key)
    }

    /**
     * Mark a default room as created asynchronously.
     */
    fun markDefaultRoomCreatedAsync(communityId: String, roomId: String, kind: String) {
        val key = defaultRoomKey(communityId, roomId, kind)
        scope.launch {
            dataStore.edit { prefs ->
                val existing = prefs[PrefsKeys.DEFAULT_ROOMS_CREATED] ?: emptySet()
                prefs[PrefsKeys.DEFAULT_ROOMS_CREATED] = existing + key
            }
        }
    }

    /**
     * Mark a default room as created (suspending/blocking until written).
     */
    suspend fun markDefaultRoomCreatedBlocking(communityId: String, roomId: String, kind: String) {
        val key = defaultRoomKey(communityId, roomId, kind)
        dataStore.edit { prefs ->
            val existing = prefs[PrefsKeys.DEFAULT_ROOMS_CREATED] ?: emptySet()
            prefs[PrefsKeys.DEFAULT_ROOMS_CREATED] = existing + key
        }
    }
}
