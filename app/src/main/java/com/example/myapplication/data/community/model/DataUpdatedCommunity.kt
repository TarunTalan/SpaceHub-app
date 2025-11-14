package com.example.myapplication.data.community.model

// Network DTO for communities in GetMyCommunitiesResponse
// Use nullable String types where server can return null; avoid Any to simplify mapping.

data class DataUpdatedCommunity(
    val role : String? = null,
    val imageUrl: String? = null,
    val imageKey: String? = null,
    val name: String? = "community name",
    val bannerUrl: String? = null,
    val bannerKey: String? = null,
    val description: String?= null,
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