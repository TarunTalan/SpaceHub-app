package com.example.myapplication.data.auth.model

data class ValidateForgotOtpRequest(
    val email: String,
    val otp: String
)
