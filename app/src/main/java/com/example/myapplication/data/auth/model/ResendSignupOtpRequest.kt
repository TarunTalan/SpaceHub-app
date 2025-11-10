package com.example.myapplication.data.auth.model

data class ResendSignupOtpRequest(
    val identifier: String,
    val sessionToken: String
)