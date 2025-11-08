package com.example.myapplication.data.dashboard.model

// Wrapper for profile API response: { status, message, data: { ...profile fields... } }
data class GetProfileEnvelope(
    val status: Int,
    val message: String?,
    val data: GetProfileResponse?
)

