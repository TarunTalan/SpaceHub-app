package com.example.myapplication.data.auth.model

data class SignupRequest(
    val firstName: String,
    val lastName: String,
    val email: String,
    val password: String
)

