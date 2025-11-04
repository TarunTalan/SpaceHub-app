package com.example.myapplication.data.community.model

data class CommunityUser(
    val banned: Boolean,
    val blocked: Boolean,
    val id: String,
    val joinDate: String,
    val role: String,
    val user: User
)