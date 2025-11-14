package com.example.myapplication.data.dashboard.model

data class UpdateProfileRequest(
    val bio: String,
    val dateOfBirth: String,
    val firstName: String,
    val lastName: String,
    val username: String
)