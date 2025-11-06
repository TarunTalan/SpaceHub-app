package com.example.myapplication.data.community.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/*
 Room entity representing a Community.
 Each community has a unique communityId returned from the server.
 */
@Entity(tableName = "communities")
data class Community(
    @PrimaryKey
    val communityId: String,

    val name: String,
    val description: String?,

    // Profile picture URL/path
    val profilePicUrl: String?,
    val profilePicLocalPath: String?,

    // Cover photo URL/path
    val coverPhotoUrl: String?,
    val coverPhotoLocalPath: String?,

    // Community details
    val category: String?,
    val memberCount: Int = 0,
    val postCount: Int = 0,
    val isPrivate: Boolean = false,
    val creatorId: String?,
    val creatorName: String?,

    // Metadata
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),

    // User's relationship with this community
    val role: String? = null,
    val isOwner: Boolean = false,
    val isMember: Boolean = false,
    val isModerator: Boolean = false
)

