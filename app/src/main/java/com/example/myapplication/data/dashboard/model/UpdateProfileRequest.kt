package com.example.myapplication.data.dashboard.model

data class UpdateProfileRequest(
    val firstName: String,
    val lastName: String,
    val bio: String,
    val location: String,
    val website: String,
    val  isPrivate: Boolean,
    val username: String,
)
