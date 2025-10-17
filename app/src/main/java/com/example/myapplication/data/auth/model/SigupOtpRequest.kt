package com.example.myapplication.data.auth.model

data class SigupOtpRequest(
    val email: String,
    val otp: String? = null,
    val type: String
)

