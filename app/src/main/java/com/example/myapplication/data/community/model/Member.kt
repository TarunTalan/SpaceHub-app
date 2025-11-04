package com.example.myapplication.data.community.model

data class Member(
    val banned: Boolean,
    val email: String,
    val joinDate: String,
    val memberId: String,
    val role: String,
    val username: String
)