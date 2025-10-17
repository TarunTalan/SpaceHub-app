package com.example.myapplication.data.auth.model

data class OtpRequest(
    val email: String,
    val otp: String? = null,
    val type: String
)

