package com.example.myapplication.data.auth

sealed class AuthResult {
    data class Success(val requiresVerification: Boolean) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

