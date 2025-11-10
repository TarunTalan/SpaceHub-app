package com.example.myapplication.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
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
    // keep a reference to application context for preferences
    private val appContext: Context = context.applicationContext

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
    suspend fun signUp(firstName: String, lastName: String, email: String, password: String, phoneNumber: String?): AuthResult {
        return safeApiCall(
            call = { api.signup(SignupRequest(firstName, lastName, email, password, phoneNumber ?: "")) },
            handle = { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body()
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

    suspend fun sendSignupOtp(email: String, tempToken: String? = null): AuthResult {
        val sessionTokenForBody = tempToken ?: tokens.getAccessToken() ?: ""
        return safeApiCall(
            call = {
                api.sendSignupOtp(SigupOtpRequest(identifier = email, otp = null, type = "REGISTRATION", sessionToken = sessionTokenForBody))
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
        // Do NOT persist sessionToken into global token store - this endpoint is expected to work without attaching
        // the global Authorization header. Instead, pass X-Skip-Auth header to the API so TokenInterceptor skips adding it.
        val req = ResendSignupOtpRequest(identifier = email, sessionToken = sessionToken ?: "")
        return safeApiCall(
            call = { api.resendSignupOtp(req) },
            handle = { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body()
                    // Prefer explicit `data` field if present (back-compat). Otherwise use `message` as fallback.
                    val tokenCandidate = try { body?.javaClass?.getDeclaredField("data")?.let { f -> f.isAccessible = true; f.get(body) as? String } } catch (_: Exception) { null }
                    val fallback = body?.let { try { it::class.java.getMethod("getMessage").invoke(it) as? String } catch (_: Exception) { null } }
                    val dataStr = tokenCandidate?.takeIf { it.isNotBlank() } ?: fallback?.takeIf { it.isNotBlank() }

                    if (!dataStr.isNullOrBlank()) AuthResult.Success(requiresVerification = true, tempToken = dataStr)
                    else AuthResult.Success(requiresVerification = true)
                } else {
                    AuthResult.Error(ResponseParser.parseError(resp.errorBody()))
                }
            }
        )
    }

    suspend fun verifySignup(email: String, otp: String, sessionToken: String? = null): AuthResult {
        val sessionTokenForBody = sessionToken ?: tokens.getAccessToken() ?: ""

        return try {
            val resp = api.sendSignupOtp(SigupOtpRequest(identifier = email, otp = otp, type = "REGISTRATION", sessionToken = sessionTokenForBody), skipAuth = "1")
            if (resp.isSuccessful) {
                val body = resp.body()
                if (body?.status == 200) {
                    try {
                        val data = body.`data`
                        val access = data.accessToken
                        val refresh = data.refreshToken
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
        } catch (_: Exception) {
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
                        // Try to extract the email from the login response
                        var emailToPersist: String = email
                        try {
                            val rawRespBodyString: String? = try { resp.raw().peekBody(1024 * 1024).string() } catch (_: Exception) { null }
                            if (!rawRespBodyString.isNullOrBlank()) {
                                try {
                                    val gson = com.google.gson.Gson()
                                    val json = gson.fromJson(rawRespBodyString, com.google.gson.JsonObject::class.java)
                                    val dataObj = json?.getAsJsonObject("data")
                                    // Check common possible fields for email in the login response
                                    val candidate = when {
                                        dataObj == null -> null
                                        dataObj.has("email") -> dataObj.get("email")?.asString
                                        dataObj.has("userEmail") -> dataObj.get("userEmail")?.asString
                                        dataObj.has("identifier") -> dataObj.get("identifier")?.asString
                                        dataObj.has("user") && dataObj.getAsJsonObject("user").has("email") -> dataObj.getAsJsonObject("user").get("email")?.asString
                                        else -> null
                                    }
                                    if (!candidate.isNullOrBlank()) emailToPersist = candidate
                                } catch (_: Exception) { /* ignore parsing errors */ }
                            }
                        } catch (_: Exception) { }

                        // Persist email as source of truth in DataStore (prefer the email found in response)
                        try { userDataManager.setEmail(emailToPersist) } catch (_: Exception) {}

                        // Also persist in SharedPreferences for backward-compatible code that relies on it
                        try {
                            val prefs: SharedPreferences = appContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                            prefs.edit {
                                putString("email", emailToPersist)
                                putString("user_email", emailToPersist)
                            }
                        } catch (_: Exception) {}

                        // Prefetch "My communities" into local DB. Pass explicit email to avoid a race
                        // where DataStore write may not be visible to Background network calls immediately.
                        try {
                            communityRepo.fetchMyCommunitiesRemote(requesterEmail = emailToPersist)
                        } catch (_: Exception) { /* ignore fetch failure */ }

                        // Fetch full profile immediately and persist into DataStore so UI shows updated profile data.
                        try {
                            val profileResp = api.getProfile(emailToPersist)
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
        password: String,
        phoneNumber: String? = null
    ): AuthResult = withContext(Dispatchers.IO) {
        when (val s = signUp(firstName, lastName, email, password, phoneNumber)) {
             is AuthResult.Error -> return@withContext s
             is AuthResult.Success -> {
                 if (!s.requiresVerification) return@withContext AuthResult.Error("Unexpected state: verification not required after signup.")
             }
         }

        when (val o = sendSignupOtp(email)) {
             is AuthResult.Error -> return@withContext o
             is AuthResult.Success -> {  }
         }

         return@withContext login(email, password)
     }
 }
