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
import com.example.myapplication.data.user.UserDataManager
import com.example.myapplication.data.community.repository.CommunityRepository

class AuthRepository(context: Context) {
    private val api = NetworkModule.createApiService(context)
    private val tokens = SharedPrefsTokenStore(context)
    private val userDataManager = UserDataManager.getInstance(context)
    private val communityRepo = CommunityRepository.getInstance(context)

    // Small helper to run network calls and centralize exception handling
    private suspend inline fun <T> safeApiCall(
        crossinline call: suspend () -> Response<T>,
        crossinline handle: suspend (Response<T>) -> AuthResult
    ): AuthResult = withContext(Dispatchers.IO) {
        try {
            val resp = call()
            if (!resp.isSuccessful && resp.code() in 500..599) {
                return@withContext AuthResult.Error("Server error. Please try again later.", statusCode = resp.code())
            }
            handle(resp)
        } catch (_: IOException) {
            AuthResult.Error("Network error. Please check your internet connection.", null)
        } catch (_: Exception) {
            AuthResult.Error("Unexpected error. Please try again.", null)
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
                        // Persist the temporary token so OTP endpoints automatically get Authorization header
                        dataStr?.let { if (it.isNotBlank()) tokens.setAccessToken(it) }
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

    // Now the flow requires presenting the temporary token (from signup) as Authorization header, so callers should
    // pass the tempToken which will be saved temporarily into SharedPrefsTokenStore just for this call.
    suspend fun sendSignupOtp(email: String, tempToken: String? = null): AuthResult {
        // persist temp token if provided so TokenInterceptor will attach it
        tempToken?.let { tokens.setAccessToken(it) }
        val sessionTokenForBody = tempToken ?: tokens.getAccessToken() ?: ""
        return safeApiCall(
            call = {
                api.sendSignupOtp(SigupOtpRequest(email = email, otp = null, type = "REGISTRATION", sessionToken = sessionTokenForBody))
            },
            handle = { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body()
                    if (body?.status == 200) {
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
    suspend fun resendSignupOtp(email: String, sessionToken: String? = null): AuthResult {
        // Persist the sessionToken so interceptor adds it to the request if present
        sessionToken?.let { tokens.setAccessToken(it) }
        val req = ResendSignupOtpRequest(email = email, sessionToken = sessionToken ?: "")
        return safeApiCall(
            call = { api.resendSignupOtp(req) },
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
                    AuthResult.Error(ResponseParser.parseError(resp.errorBody()))
                }
            }
        )
    }

    // Verify signup OTP. The same endpoint is used; success expected when status == 200
    // On success the server returns final access/refresh tokens inside SignupOtpResponse.data
    suspend fun verifySignup(email: String, otp: String, sessionToken: String? = null): AuthResult {
        val sessionTokenForBody = sessionToken ?: tokens.getAccessToken() ?: ""
        // If a sessionToken was provided explicitly, ensure it's persisted so interceptor attaches it
        sessionToken?.let { tokens.setAccessToken(it) }

        return try {
            val resp = api.sendSignupOtp(SigupOtpRequest(email = email, otp = otp, type = "REGISTRATION", sessionToken = sessionTokenForBody), skipAuth = "1")
            if (resp.isSuccessful) {
                val body = resp.body()
                if (body?.status == 200) {
                    try {
                        val otpBody = body
                        val data = otpBody?.`data`
                        val access = data?.accessToken
                        val refresh = data?.refreshToken
                        access?.let { tokens.setAccessToken(it) }
                        refresh?.let { tokens.setRefreshToken(it) }
                    } catch (_: Exception) { }
                    AuthResult.Success(requiresVerification = false)
                } else {
                    AuthResult.Error(body?.message ?: "OTP verification failed.")
                }
            } else {
                AuthResult.Error(ResponseParser.parseError(resp.errorBody()))
            }
        } catch (e: Exception) {
            AuthResult.Error("Network error during OTP verification.")
        }
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
                    if (ok) {
                        // Persist email as source of truth in DataStore
                        try { userDataManager.setEmail(email) } catch (_: Exception) {}

                        // Prefetch "My communities" into local DB. Pass explicit email to avoid a race
                        // where DataStore write may not be visible to Background network calls immediately.
                        try {
                            communityRepo.fetchMyCommunitiesRemote(requesterEmail = email)
                        } catch (_: Exception) { /* ignore fetch failure */ }

                        // Fetch full profile immediately and persist into DataStore so UI shows updated profile data.
                        try {
                            val profileResp = api.getProfile(email)
                            if (profileResp.isSuccessful) {
                                val envelope = profileResp.body()
                                val p = envelope?.data
                                p?.let {
                                    try {
                                        // Map available fields from GetProfileResponse (all nullable now)
                                        userDataManager.updateProfile(
                                            username = it.username?.takeIf { v -> v.isNotBlank() },
                                            firstName = it.firstName?.takeIf { v -> v.isNotBlank() },
                                            lastName = it.lastName?.takeIf { v -> v.isNotBlank() },
                                            email = it.email,
                                            bio = it.bio,
                                            dateOfBirth = it.dateOfBirth,
                                            location = null,
                                            website = null,
                                            avatarUrl = it.avatarPreviewUrl?.takeIf { v -> v.isNotBlank() } ?: it.avatarKey?.takeIf { v -> v.isNotBlank() },
                                            coverPhotoUrl = null,
                                            followersCount = null,
                                            followingCount = null,
                                            isPrivate = null
                                        )

                                        // Also set profile image explicitly (convenience)
                                        userDataManager.updateProfileImage(it.avatarPreviewUrl ?: it.avatarKey)
                                    } catch (_: Exception) { /* ignore persistence errors */ }
                                }
                            }
                        } catch (_: Exception) { /* ignore profile fetch failure */ }

                        AuthResult.Success(requiresVerification = false)
                    }
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
