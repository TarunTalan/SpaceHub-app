package com.example.myapplication.data.auth.model

data class ResetPasswordRequest(
    val email: String,
    val newPassword: String,
    val tempToken: String
)
