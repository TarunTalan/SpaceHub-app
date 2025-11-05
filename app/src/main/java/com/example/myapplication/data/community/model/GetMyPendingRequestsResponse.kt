package com.example.myapplication.data.community.model

data class GetMyPendingRequestsResponse(
    val status: Int,
    val message: String,
    val data: List<PendingRequest>
)

data class PendingRequest(
    val id: String,
    val communityId: String,
    val communityName: String,
    val communityImageUrl: String?,
    val userEmail: String,
    val userName: String?,
    val userAvatarUrl: String?,
    val requestedAt: String,
    val status: String // e.g., "PENDING", "APPROVED", "REJECTED"
)

