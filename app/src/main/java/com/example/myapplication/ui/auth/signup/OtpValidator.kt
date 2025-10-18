package com.example.myapplication.ui.auth.signup

/**
 * Centralized OTP validation logic.
 */
object OtpValidator {
    enum class Result {
        NONE, // valid
        EMPTY,
        LENGTH,
        FORMAT
    }

    private val otpRegex = Regex("^[0-9]{6}$")

    fun validate(otp: String): Result {
        val trimmed = otp.trim()
        if (trimmed.isEmpty()) return Result.EMPTY
        if (trimmed.length != 6) return Result.LENGTH
        if (!otpRegex.matches(trimmed)) return Result.FORMAT
        return Result.NONE
    }

    fun isValid(otp: String): Boolean = validate(otp) == Result.NONE
}

