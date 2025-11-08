package com.example.myapplication.data.dashboard.model

data class UpdateProfileRequest(
    val bio: String,
    val currentPassword: String,
    val dateOfBirth: String,
    val firstName: String,
    val lastName: String,
    val newEmail: String,
    val newPassword: String,
    val username: String
)