package com.example.myapplication.data.groups.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity to cache local group (mirrors Community entity shape for UI usage).
 */
@Entity(tableName = "local_groups")
data class LocalGroup(
    @PrimaryKey
    val groupId: String,
    val name: String,
    val description: String?,
    val imageUrl: String?,
    val memberEmails: List<String> = emptyList(),
    val memberCount: Int = 0,
    val createdByEmail: String?,
    val chatRoomCode: String?,
    val createdAt: String?,
    val updatedAt: String?,
    // relationship flags for quick UI decisions
    val isOwner: Boolean = false,
    val isMember: Boolean = false
)
