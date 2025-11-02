package com.example.myapplication.data.dashboard.model

data class GetProfileResponse(
    val id: String,
    val firstName: String,
    val lastName: String,
    val username: String,
    val email: String,
    val avatarKey : String?,
    val avatarPreviewUrl : String?,
    val coverKey : String?,
    val coverPreviewUrl : String?,
    val bio: String?,
    val location: String?,
    val website: String?,
    val dateOfBirth: String?,
    val isPrivate : Boolean,
    val createdAt: String,
    val updatedAt: String
)
