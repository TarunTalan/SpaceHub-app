package com.example.myapplication.data.user

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

// DataStore delegate
private val Context.dataStore by preferencesDataStore(name = "user_preferences")

/**
 * Centralized manager for all user profile and community data.
 * Now uses Jetpack DataStore (Preferences) for modern, type-safe, async data persistence.
 * Provides both Flow (recommended) and LiveData for reactive UI updates.
 * Migration from SharedPreferences completed.
 */
class UserDataManager private constructor(private val context: Context) {

    private val dataStore = context.applicationContext.dataStore
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // DataStore Keys
    private object PrefsKeys {
        val PROFILE_IMAGE_URL = stringPreferencesKey("profile_image_url")
        val PROFILE_IMAGE_PATH = stringPreferencesKey("profile_image_path")
        val PROFILE_IMAGE_URI = stringPreferencesKey("profile_image_uri")
        val PROFILE_IMAGE_RES = intPreferencesKey("profile_image_res")
        val PROFILE_IMAGE_UPDATED_AT = longPreferencesKey("profile_image_updated_at")

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
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserDataManager(context).also { INSTANCE = it }
            }
        }
    }

    // Flow-based reactive data streams (recommended - use these in new code)
    val profileImageUrlFlow: Flow<String?> = dataStore.data.map { it[PrefsKeys.PROFILE_IMAGE_URL] }
    val profileImagePathFlow: Flow<String?> = dataStore.data.map { it[PrefsKeys.PROFILE_IMAGE_PATH] }
    val profileImageUriFlow: Flow<String?> = dataStore.data.map { it[PrefsKeys.PROFILE_IMAGE_URI] }
    val profileImageResFlow: Flow<Int?> = dataStore.data.map { it[PrefsKeys.PROFILE_IMAGE_RES]?.takeIf { res -> res != 0 } }

    val usernameFlow: Flow<String?> = dataStore.data.map { it[PrefsKeys.USERNAME] }
    val firstNameFlow: Flow<String?> = dataStore.data.map { it[PrefsKeys.FIRST_NAME] }
    val lastNameFlow: Flow<String?> = dataStore.data.map { it[PrefsKeys.LAST_NAME] }
    val emailFlow: Flow<String?> = dataStore.data.map { it[PrefsKeys.EMAIL] }
    val bioFlow: Flow<String?> = dataStore.data.map { it[PrefsKeys.BIO] }
    val dateOfBirthFlow: Flow<String?> = dataStore.data.map { it[PrefsKeys.DOB] }
    val locationFlow: Flow<String?> = dataStore.data.map { it[PrefsKeys.LOCATION] }
    val websiteFlow: Flow<String?> = dataStore.data.map { it[PrefsKeys.WEBSITE] }

    val followersCountFlow: Flow<Int> = dataStore.data.map { it[PrefsKeys.FOLLOWERS_COUNT] ?: 0 }
    val followingCountFlow: Flow<Int> = dataStore.data.map { it[PrefsKeys.FOLLOWING_COUNT] ?: 0 }
    val isPrivateFlow: Flow<Boolean> = dataStore.data.map { it[PrefsKeys.IS_PRIVATE] ?: false }

    // LiveData for backward compatibility (auto-converts from Flow)
    val profileImageUrl: LiveData<String?> = profileImageUrlFlow.asLiveData()
    val profileImagePath: LiveData<String?> = profileImagePathFlow.asLiveData()
    val profileImageUri: LiveData<String?> = profileImageUriFlow.asLiveData()
    val profileImageRes: LiveData<Int?> = profileImageResFlow.asLiveData()

    val username: LiveData<String?> = usernameFlow.asLiveData()
    val firstName: LiveData<String?> = firstNameFlow.asLiveData()
    val lastName: LiveData<String?> = lastNameFlow.asLiveData()
    val email: LiveData<String?> = emailFlow.asLiveData()
    val bio: LiveData<String?> = bioFlow.asLiveData()
    val dateOfBirth: LiveData<String?> = dateOfBirthFlow.asLiveData()
    val location: LiveData<String?> = locationFlow.asLiveData()
    val website: LiveData<String?> = websiteFlow.asLiveData()

    val followersCount: LiveData<Int> = followersCountFlow.asLiveData()
    val followingCount: LiveData<Int> = followingCountFlow.asLiveData()
    val isPrivate: LiveData<Boolean> = isPrivateFlow.asLiveData()

    /**
     * Update profile image from server response.
     * Handles URL, local file path, content URI, or drawable resource.
     * Automatically deletes old cached files.
     */
    fun updateProfileImage(
        url: String? = null,
        localPath: String? = null,
        contentUri: Uri? = null,
        drawableRes: Int? = null
    ) {
        scope.launch {
            // Delete old cached file if being replaced
            val oldPath = dataStore.data.first()[PrefsKeys.PROFILE_IMAGE_PATH]
            if (!oldPath.isNullOrBlank() && oldPath != localPath) {
                try {
                    File(oldPath).takeIf { it.exists() }?.delete()
                } catch (_: Exception) {}
            }

            dataStore.edit { prefs ->
                // Update URL and timestamp for cache busting
                if (url != null) {
                    prefs[PrefsKeys.PROFILE_IMAGE_URL] = url
                    prefs[PrefsKeys.PROFILE_IMAGE_UPDATED_AT] = System.currentTimeMillis()
                } else {
                    prefs.remove(PrefsKeys.PROFILE_IMAGE_URL)
                }

                // Update local path (mutually exclusive)
                when {
                    localPath != null -> {
                        prefs[PrefsKeys.PROFILE_IMAGE_PATH] = localPath
                        prefs.remove(PrefsKeys.PROFILE_IMAGE_RES)
                        prefs.remove(PrefsKeys.PROFILE_IMAGE_URI)
                    }
                    contentUri != null -> {
                        prefs[PrefsKeys.PROFILE_IMAGE_URI] = contentUri.toString()
                        prefs.remove(PrefsKeys.PROFILE_IMAGE_PATH)
                        prefs.remove(PrefsKeys.PROFILE_IMAGE_RES)
                    }
                    drawableRes != null && drawableRes != 0 -> {
                        prefs[PrefsKeys.PROFILE_IMAGE_RES] = drawableRes
                        prefs.remove(PrefsKeys.PROFILE_IMAGE_PATH)
                        prefs.remove(PrefsKeys.PROFILE_IMAGE_URI)
                    }
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

                avatarUrl?.let {
                    prefs[PrefsKeys.PROFILE_IMAGE_URL] = it
                    prefs[PrefsKeys.PROFILE_IMAGE_UPDATED_AT] = System.currentTimeMillis()
                }
                coverPhotoUrl?.let { prefs[PrefsKeys.COVER_PHOTO_URL] = it }

                followersCount?.let { prefs[PrefsKeys.FOLLOWERS_COUNT] = it }
                followingCount?.let { prefs[PrefsKeys.FOLLOWING_COUNT] = it }
                isPrivate?.let { prefs[PrefsKeys.IS_PRIVATE] = it }
            }
        }
    }

    /**
     * Get the best available profile image source in priority order
     * Suspending function for Flow/coroutine usage
     */
    suspend fun getBestProfileImageSource(): Any? {
        val prefs = dataStore.data.first()
        return prefs[PrefsKeys.PROFILE_IMAGE_URL]?.takeIf { it.isNotBlank() }
            ?: prefs[PrefsKeys.PROFILE_IMAGE_PATH]?.takeIf { it.isNotBlank() }?.let {
                File(it).takeIf { f -> f.exists() }
            }
            ?: prefs[PrefsKeys.PROFILE_IMAGE_URI]?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            ?: prefs[PrefsKeys.PROFILE_IMAGE_RES]?.takeIf { it != 0 }
    }

    /**
     * Clear all user data (for logout)
     */
    fun clear() {
        scope.launch {
            // Delete cached files
            val path = dataStore.data.first()[PrefsKeys.PROFILE_IMAGE_PATH]
            path?.let {
                try { File(it).takeIf { f -> f.exists() }?.delete() } catch (_: Exception) {}
            }

            dataStore.edit { it.clear() }
        }
    }

    /**
     * Convenience getters for immediate (non-observed) sync values
     * Note: These are blocking calls - prefer Flow in coroutines when possible
     */
    suspend fun getUsernameSync(): String? = dataStore.data.first()[PrefsKeys.USERNAME]
    suspend fun getEmailSync(): String? = dataStore.data.first()[PrefsKeys.EMAIL]
    suspend fun getFirstNameSync(): String? = dataStore.data.first()[PrefsKeys.FIRST_NAME]
    suspend fun getLastNameSync(): String? = dataStore.data.first()[PrefsKeys.LAST_NAME]
}

