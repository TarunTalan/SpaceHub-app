package com.example.myapplication.ui.common

import android.util.Patterns
import com.example.myapplication.ui.auth.signup.OtpValidator

/**
 * Centralized input validation helpers for email, password and OTP.
 * Keep this small and dependency-free so fragments can share logic.
 */
object InputValidator {

    enum class EmailResult { VALID, EMPTY, INVALID_FORMAT }
    enum class PasswordResult { VALID, EMPTY, TOO_SHORT }

    // Validate email and return result
    fun validateEmail(email: String?): EmailResult {
        val trimmed = email?.trim().orEmpty()
        if (trimmed.isEmpty()) return EmailResult.EMPTY
        return if (Patterns.EMAIL_ADDRESS.matcher(trimmed).matches()) EmailResult.VALID
        else EmailResult.INVALID_FORMAT
    }

    fun isEmailValid(email: String?): Boolean = validateEmail(email) == EmailResult.VALID

    // Basic password validation: only checks emptiness and minimal length here
    fun validatePassword(password: String?, minLen: Int = 6): PasswordResult {
        val p = password.orEmpty()
        if (p.isEmpty()) return PasswordResult.EMPTY
        if (p.length < minLen) return PasswordResult.TOO_SHORT
        return PasswordResult.VALID
    }

    fun isPasswordValid(password: String?, minLen: Int = 6): Boolean = validatePassword(password, minLen) == PasswordResult.VALID

    // Additional password rules used in signup: uppercase, digit and special-char checks
    fun hasUppercase(password: String?): Boolean {
        val p = password.orEmpty()
        return p.any { it.isUpperCase() }
    }

    fun hasDigit(password: String?): Boolean {
        val p = password.orEmpty()
        return p.any { it.isDigit() }
    }

    fun hasSpecialChar(password: String?): Boolean {
        val p = password.orEmpty()
        // consider any non-alphanumeric character as special
        return p.any { !it.isLetterOrDigit() }
    }

    // Delegate to existing OtpValidator for OTP checks
    fun validateOtp(otp: String): OtpValidator.Result = OtpValidator.validate(otp)
    fun isOtpValid(otp: String): Boolean = OtpValidator.isValid(otp)
}
