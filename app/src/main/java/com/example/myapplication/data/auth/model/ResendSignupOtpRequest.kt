package com.example.myapplication.data.auth.model

data class ResendSignupOtpRequest(
    val email: String,
    val sessionToken: String
)