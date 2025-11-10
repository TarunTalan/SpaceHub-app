package com.example.myapplication.data.auth.model

data class SigupOtpRequest(
    val identifier: String,
    val otp: String? = null,
    val type: String,
    val sessionToken: String

)

