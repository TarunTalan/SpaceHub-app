package com.example.myapplication.data.auth

import android.content.Context
import com.example.myapplication.data.auth.model.*
import com.example.myapplication.data.network.NetworkModule
import com.example.myapplication.data.network.SharedPrefsTokenStore
import com.example.myapplication.data.network.ResponseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.IOException

class AuthRepository(context: Context) {
    private val api = NetworkModule.createApiService(context)
    private val tokens = SharedPrefsTokenStore(context)

    // Small helper to run network calls and centralize exception handling
    private suspend inline fun <T> safeApiCall(
        crossinline call: suspend () -> Response<T>,
        crossinline handle: (Response<T>) -> AuthResult
    ): AuthResult = withContext(Dispatchers.IO) {
        try {
            val resp = call()
            handle(resp)
        } catch (_: IOException) {
            AuthResult.Error("Network error. Please check your internet connection.")
        } catch (_: Exception) {
            AuthResult.Error("Unexpected error. Please try again.")
        }
    }

    // Signup: submit user details. Server may respond with status 200/201 meaning signup accepted and OTP required.
    suspend fun signUp(firstName: String, lastName: String, email: String, password: String): AuthResult {
        return safeApiCall(
            call = { api.signup(SignupRequest(firstName, lastName, email, password)) },
            handle = { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body()
                    // If backend returns a data field (temp token), expose it in Success.tempToken
                    if (body?.status == 200 || body?.status == 201) {
                        val dataStr = try { body.data } catch (_: Exception) { null }
                        return@safeApiCall AuthResult.Success(requiresVerification = true, tempToken = dataStr)
                    } else {
                        return@safeApiCall AuthResult.Error(body?.message ?: "Signup failed.")
                    }
                } else {
                    AuthResult.Error(ResponseParser.parseError(resp.errorBody()))
                }
            }
        )
    }

    // Send or request signup OTP. The API uses the same endpoint for send/verify; treat a 200 status as success.
    suspend fun sendSignupOtp(email: String): AuthResult {
        return safeApiCall(
            call = { api.sendSignupOtp(SigupOtpRequest(email = email, otp = null, type = "REGISTRATION")) },
            handle = { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body()
                    if (body?.status == 200) {
                        // SignupOtpResponse has no `data` property (only status + message), so just return success
                        AuthResult.Success(requiresVerification = true)
                    }
                    else AuthResult.Error(body?.message ?: "Failed to send OTP.")
                } else {
                    AuthResult.Error(ResponseParser.parseError(resp.errorBody()))
                }
            }
        )
    }

    // Resend signup OTP using temporary token returned by signup/OTP endpoints.
    suspend fun resendSignupOtp(email: String, sessionToken: String): AuthResult {
        return safeApiCall(
            call = { api.resendSignupOtp(ResendSignupOtpRequest(email = email, sessionToken = sessionToken)) },
            handle = { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body()
                    val dataStr = try { body?.data } catch (_: Exception) { null }
                    if (!dataStr.isNullOrBlank()) {
                        AuthResult.Success(requiresVerification = true, tempToken = dataStr)
                    } else {
                        AuthResult.Success(requiresVerification = true)
                    }
                } else {
                    // Removed debug logging; return parsed server error
                    AuthResult.Error(ResponseParser.parseError(resp.errorBody()))
                }
            }
        )
    }

    // Verify signup OTP. The same endpoint is used; success expected when status == 200
    suspend fun verifySignup(email: String, otp: String): AuthResult {
        return safeApiCall(
            call = { api.sendSignupOtp(SigupOtpRequest(email = email, otp = otp, type = "REGISTRATION")) },
            handle = { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body()
                    if (body?.status == 200) AuthResult.Success(requiresVerification = false)
                    else AuthResult.Error(body?.message ?: "OTP verification failed.")
                } else {
                    AuthResult.Error(ResponseParser.parseError(resp.errorBody()))
                }
            }
        )
    }

    // Forgot-password: request OTP for password reset
    suspend fun sendForgotPasswordOtp(email: String): AuthResult {
        return safeApiCall(
            call = { api.forgotPassword(ForgotPasswordRequest(email = email)) },
            handle = { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body()
                    // backend returns status and data (data may be a message or temp token / OTP)
                    if (body?.status == 200) {
                        // Return the backend `data` string in tempToken for internal use; do not log or display it here
                        val dataStr = try { body.data } catch (_: Exception) { null }
                        AuthResult.Success(requiresVerification = true, tempToken = dataStr)
                    } else {
                        AuthResult.Error(body?.message ?: "Failed to send OTP for password reset.")
                    }
                } else {
                    AuthResult.Error(ResponseParser.parseError(resp.errorBody()))
                }
            }
        )
    }

    // Resend forgot-password OTP using the temporary token issued by the server.
    suspend fun resendForgotPasswordOtp(tempToken: String): AuthResult {
        return safeApiCall(
            call = { api.resendForgotOtp(ResendForgotOtpRequest(tempToken = tempToken)) },
            handle = { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body()
                    // The resend endpoint returns a `data` string containing the (new) temp token or message.
                    val dataStr = try { body?.data } catch (_: Exception) { null }
                    if (!dataStr.isNullOrBlank()) {
                        AuthResult.Success(requiresVerification = true, tempToken = dataStr)
                    } else {
                        AuthResult.Error(body?.message ?: "Failed to resend OTP.")
                    }
                } else {
                    AuthResult.Error(ResponseParser.parseError(resp.errorBody()))
                }
            }
        )
    }

    // Forgot-password: verify OTP. Server may return access/refresh tokens upon successful verification.
    suspend fun verifyForgotPasswordOtp(email: String, otp: String): AuthResult {
        return safeApiCall(
            call = { api.validateForgotOtp(ValidateForgotOtpRequest(email = email, otp = otp)) },
            handle = { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body()
                    if (body?.status == 200) {
                        // persist tokens if present
                        val data = body.data
                        val access = data?.accessToken
                        val refresh = data?.refreshToken
                        access?.let { tokens.setAccessToken(it) }
                        refresh?.let { tokens.setRefreshToken(it) }

                        // IMPORTANT: expose the access token returned by OTP verification as tempToken
                        // so the UI flows (reset password) can use the exact token the server issued for this OTP session.
                        return@safeApiCall AuthResult.Success(requiresVerification = false, tempToken = access)
                    } else {
                        AuthResult.Error(body?.message ?: "OTP verification failed.")
                    }
                } else {
                    AuthResult.Error(ResponseParser.parseError(resp.errorBody()))
                }
            }
        )
    }

    // Login: obtain tokens and persist them
    suspend fun login(email: String, password: String): AuthResult {
        return safeApiCall(
            call = { api.login(LoginRequest(email, password)) },
            handle = { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body()
                    val data = body?.data
                    val access = data?.accessToken
                    val refresh = data?.refreshToken
                    access?.let { tokens.setAccessToken(it) }
                    refresh?.let { tokens.setRefreshToken(it) }

                    val ok = !access.isNullOrBlank() && !refresh.isNullOrBlank()
                    if (ok) AuthResult.Success(requiresVerification = false)
                    else AuthResult.Error(body?.message ?: "Login succeeded but tokens missing.")
                } else {
                    AuthResult.Error(ResponseParser.parseError(resp.errorBody()))
                }
            }
        )
    }

    // Reset password endpoint: accepts email, newPassword, tempToken
    suspend fun resetPassword(email: String, newPassword: String, tempToken: String): AuthResult {
        return safeApiCall(
            call = { api.resetPassword(ResetPasswordRequest(email = email, newPassword = newPassword, tempToken = tempToken)) },
            handle = { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body()
                    if (body?.status == 200) AuthResult.Success(requiresVerification = false)
                    else AuthResult.Error(body?.message ?: "Failed to reset password.")
                } else {
                    AuthResult.Error(ResponseParser.parseError(resp.errorBody()))
                }
            }
        )
    }

    // Convenience: sign up, request OTP, then login. Returns the final AuthResult from login or the first error encountered.
    @Suppress("unused")
    suspend fun signUpThenSendOtpThenLogin(
        firstName: String,
        lastName: String,
        email: String,
        password: String
    ): AuthResult = withContext(Dispatchers.IO) {
        when (val s = signUp(firstName, lastName, email, password)) {
            is AuthResult.Error -> return@withContext s
            is AuthResult.Success -> {
                if (!s.requiresVerification) return@withContext AuthResult.Error("Unexpected state: verification not required after signup.")
            }
        }

        when (val o = sendSignupOtp(email)) {
            is AuthResult.Error -> return@withContext o
            is AuthResult.Success -> { /* continue */ }
        }

        return@withContext login(email, password)
    }
}
