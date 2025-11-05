package com.example.myapplication.data.community.model

// Network DTO for communities in GetMyCommunitiesResponse
// Use nullable String types where server can return null; avoid Any to simplify mapping.

data class DataUpdatedCommunity(
    val imageUrl: String?,
    val imageKey: String?,
    val name: String,
    val bannerUrl: String?,
    val description: String?,
    val communityId: String,
    // Keep optional fields nullable to be forward-compatible
    val avatarUrl: String? = null,
    val chatRooms: List<Any?>? = null,
    val communityUsers: List<CommunityUser>? = null,
    val createdAt: String? = null,
    val createdBy: CreatedBy? = null,
    val id: String? = null,
    val members: List<Any?>? = null,
    val pendingRequests: List<Any?>? = null,
    val updatedAt: String? = null
)