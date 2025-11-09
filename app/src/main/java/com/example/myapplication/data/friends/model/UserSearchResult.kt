package com.example.myapplication.data.friends.model

import com.example.myapplication.BuildConfig

/**
 * UI-friendly model for displaying user search results.
 * Maps from Content model and adds UI state flags.
 */
data class UserSearchResult(
    val userId: String,
    val username: String,
    val email: String,
    val avatarUrl: String?,
    val firstName: String? = null,
    val lastName: String? = null,
    val bio: String? = null,
    val isFriend: Boolean = false,
    val isPending: Boolean = false
) {
    companion object {
        /**
         * Create UserSearchResult from Content model
         */
        fun fromContent(content: Content): UserSearchResult {
            val resolved = when {
                !content.avatarPreviewUrl.isNullOrBlank() -> content.avatarPreviewUrl
                !content.avatarUrl.isNullOrBlank() -> {
                    val raw = content.avatarUrl.trim()
                    if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw
                    else BuildConfig.BASE_URL.trimEnd('/') + "/" + raw.trimStart('/')
                }
                !content.avatarKey.isNullOrBlank() -> BuildConfig.BASE_URL.trimEnd('/') + "/" + content.avatarKey.trimStart('/')
                else -> null
            }

            return UserSearchResult(
                userId = content.userId,
                username = content.username,
                email = content.email,
                avatarUrl = resolved,
                firstName = content.firstName,
                lastName = content.lastName
            )
        }
    }
}
