package com.example.myapplication.ui.common

import android.util.Patterns
import com.example.myapplication.ui.auth.signup.OtpValidator

/**
 * Centralized input validation helpers for email, password and OTP.
 * Keep this small and dependency-free so fragments can share logic.
 */
object InputValidator {

    enum class EmailResult { VALID, EMPTY, INVALID_FORMAT, TOO_LONG, HAS_SPACE }
    enum class PasswordResult { VALID, EMPTY, TOO_SHORT, TOO_LONG, HAS_SPACE, NO_LOWERCASE }
    enum class UsernameResult { VALID, EMPTY, HAS_SPACE, HAS_DIGIT, INVALID_CHAR }

    // Validate email and return result
    fun validateEmail(email: String?): EmailResult {
        val trimmed = email?.trim().orEmpty()
        if (trimmed.isEmpty()) return EmailResult.EMPTY
        if (trimmed.length > 50) return EmailResult.TOO_LONG
        if (trimmed.contains(' ')) return EmailResult.HAS_SPACE
        return if (Patterns.EMAIL_ADDRESS.matcher(trimmed).matches()) EmailResult.VALID
        else EmailResult.INVALID_FORMAT
    }

    fun isEmailValid(email: String?): Boolean = validateEmail(email) == EmailResult.VALID

    // Basic password validation: only checks emptiness and minimal length here
    fun validatePassword(password: String?, minLen: Int = 6): PasswordResult {
        val p = password.orEmpty()
        if (p.isEmpty()) return PasswordResult.EMPTY
        if (p.length < minLen) return PasswordResult.TOO_SHORT
        if (p.length > 25) return PasswordResult.TOO_LONG
        if (p.contains(' ')) return PasswordResult.HAS_SPACE
        if (!hasLowercase(p)) return PasswordResult.NO_LOWERCASE
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

    // New helper: at least one lowercase character
    fun hasLowercase(password: String?): Boolean {
        val p = password.orEmpty()
        return p.any { it.isLowerCase() }
    }

    // New helper: validate username according to specified rules
    fun validateUsername(username: String?): UsernameResult {
        val u = username.orEmpty()
        if (u.isEmpty()) return UsernameResult.EMPTY
        if (u.contains(' ')) return UsernameResult.HAS_SPACE
        if (u.any { it.isDigit() }) return UsernameResult.HAS_DIGIT
        if (!u.all { it.isLetter() }) return UsernameResult.INVALID_CHAR
        return UsernameResult.VALID
    }

    // Delegate to existing OtpValidator for OTP checks
    fun validateOtp(otp: String): OtpValidator.Result = OtpValidator.validate(otp)
    fun isOtpValid(otp: String): Boolean = OtpValidator.isValid(otp)

    // New strict password validation used across signup/new-password flows
    enum class PasswordStrictResult {
        VALID,
        EMPTY,
        HAS_SPACE,
        TOO_SHORT,
        TOO_LONG,
        NO_UPPERCASE,
        NO_LOWERCASE,
        NO_DIGIT,
        NO_SPECIAL_CHAR
    }

    /**
     * Validate password with strict rules: length between minLen..maxLen, no spaces,
     * at least one uppercase, one lowercase, one digit and one special char.
     */
    fun validatePasswordStrict(password: String?, minLen: Int = 8, maxLen: Int = 25): PasswordStrictResult {
        val p = password.orEmpty()
        if (p.isEmpty()) return PasswordStrictResult.EMPTY
        if (p.contains(' ')) return PasswordStrictResult.HAS_SPACE
        if (p.length < minLen) return PasswordStrictResult.TOO_SHORT
        if (p.length > maxLen) return PasswordStrictResult.TOO_LONG
        if (!p.any { it.isUpperCase() }) return PasswordStrictResult.NO_UPPERCASE
        if (!p.any { it.isLowerCase() }) return PasswordStrictResult.NO_LOWERCASE
        if (!p.any { it.isDigit() }) return PasswordStrictResult.NO_DIGIT
        if (!p.any { !it.isLetterOrDigit() }) return PasswordStrictResult.NO_SPECIAL_CHAR
        return PasswordStrictResult.VALID
    }
}
