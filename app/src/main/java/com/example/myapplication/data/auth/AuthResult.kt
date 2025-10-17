package com.example.myapplication.data.auth

sealed class AuthResult {
    /**
     * @param requiresVerification true when the caller must show an OTP screen
     * @param tempToken optional temporary token returned by backend (for reset-password flows)
     */
    data class Success(val requiresVerification: Boolean, val tempToken: String? = null) : AuthResult()
    data class Error(val message: String) : AuthResult()
}
