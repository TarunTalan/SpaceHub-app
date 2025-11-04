package com.example.myapplication.data.community.model

data class DataXX(
    val avatarUrl: Any,
    val bannerUrl: Any,
    val chatRooms: List<Any?>,
    val communityId: String,
    val communityUsers: List<CommunityUser>,
    val createdAt: String,
    val createdBy: CreatedBy,
    val description: String,
    val id: String,
    val imageUrl: String,
    val members: List<Any?>,
    val name: String,
    val pendingRequests: List<Any?>,
    val updatedAt: String
)