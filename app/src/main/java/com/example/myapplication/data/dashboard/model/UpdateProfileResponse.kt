package com.example.myapplication.data.dashboard.model

data class UpdateProfileResponse(
    val `data`: ProfileData,
    val message: String,
    val status: Int
)