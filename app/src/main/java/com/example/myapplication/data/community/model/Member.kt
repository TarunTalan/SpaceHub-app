package com.example.myapplication.data.community.model

data class Member(
    val avatarKey: String? = null,
    val avatarPreviewUrl: String? = null,
    val banned: Boolean? = null,
    val bio: String? = null,
    val email: String? = null,
    val joinDate: String? = null,
    val location: String? = null,
    val memberId: Int? = null,
    val role: String? = null,
    val username: String? = null,
    val website: String? = null
)