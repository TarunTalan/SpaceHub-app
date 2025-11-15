package com.example.myapplication.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.example.myapplication.data.auth.model.*
import com.example.myapplication.data.network.NetworkModule
import com.example.myapplication.data.network.SharedPrefsTokenStore
import com.example.myapplication.data.network.ResponseParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    // Background scope for fire-and-forget tasks started by the repository
    private val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

    // Login: obtain tokens and persist them
    suspend fun login(email: String, password: String): AuthResult {
        return withContext(Dispatchers.IO) {
            try {
                val resp = api.login(LoginRequest(email, password))
                if (!resp.isSuccessful) {
                    return@withContext AuthResult.Error(ResponseParser.parseError(resp.errorBody()))
                }

                val body = resp.body()
                val data = body?.data
                val access = data?.accessToken
                val refresh = data?.refreshToken
                access?.let { tokens.setAccessToken(it) }
                refresh?.let { tokens.setRefreshToken(it) }

                val ok = !access.isNullOrBlank() && !refresh.isNullOrBlank()
                if (!ok) return@withContext AuthResult.Error(body?.message ?: "Login failed.")

                // Prefer typed email returned in the response body (Data.email) if available.
                var emailToPersist: String? = null
                try {
                    val typedEmail = data?.email
                    if (!typedEmail.isNullOrBlank() && typedEmail.contains("@")) {
                        emailToPersist = typedEmail
                        Log.d("AuthRepository", "login: using typed email from response: $emailToPersist")
                    }
                } catch (_: Exception) {}

                // If typed email not present, fallback to raw JSON defensive parsing
                if (emailToPersist.isNullOrBlank()) {
                    try {
                        val rawRespBodyString: String? = try { resp.raw().peekBody(1024 * 1024).string() } catch (_: Exception) { null }
                        if (!rawRespBodyString.isNullOrBlank()) {
                            val gson = com.google.gson.Gson()
                            val json = gson.fromJson(rawRespBodyString, com.google.gson.JsonObject::class.java)
                            val dataObj = json?.getAsJsonObject("data")
                            val candidate = when {
                                dataObj == null -> null
                                dataObj.has("email") -> dataObj.get("email")?.asString
                                dataObj.has("userEmail") -> dataObj.get("userEmail")?.asString
                                dataObj.has("user") && dataObj.getAsJsonObject("user").has("email") -> dataObj.getAsJsonObject("user").get("email")?.asString
                                dataObj.has("identifier") -> dataObj.get("identifier")?.asString
                                else -> null
                            }
                            Log.d("AuthRepository", "login: parsed candidate from response='$candidate'")
                            if (!candidate.isNullOrBlank() && candidate.contains("@")) emailToPersist = candidate
                        }
                    } catch (_: Exception) {}
                }

                if (emailToPersist.isNullOrBlank() && email.contains("@")) emailToPersist = email

                if (!emailToPersist.isNullOrBlank()) {
                    try { userDataManager.updateProfileBlocking(email = emailToPersist) } catch (e: Exception) { Log.w("AuthRepository", "updateProfileBlocking failed: ${e.message}"); try { userDataManager.setEmail(emailToPersist) } catch (_: Exception) {} }
                    try { com.example.myapplication.data.session.SessionManager.setLoginEmail(appContext, emailToPersist) } catch (_: Exception) {}
                    try { val prefs: SharedPreferences = appContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE); prefs.edit(commit = true) { putString("email", emailToPersist); putString("user_email", emailToPersist) } } catch (_: Exception) {}

                    // Immediately prefetch communities/profile synchronously so downstream callers won't use the phone identifier.
                    try {
                        Log.d("AuthRepository", "login: immediately fetching communities for persisted email $emailToPersist")
                        val commRes = communityRepo.fetchMyCommunitiesRemote(requesterEmail = emailToPersist)
                        Log.d("AuthRepository", "login: immediate community fetch result: $commRes")
                    } catch (e: Exception) {
                        Log.w("AuthRepository", "login: immediate community fetch failed: ${e.message}")
                    }

                    try {
                        Log.d("AuthRepository", "login: immediately fetching profile for $emailToPersist")
                        val profileResp = api.getProfile(emailToPersist)
                        Log.d("AuthRepository", "login: immediate profileResp successful=${profileResp.isSuccessful}")
                        if (profileResp.isSuccessful) {
                            val envelope = profileResp.body()
                            val p = envelope?.data
                            p?.let {
                                try {
                                    val avatarFinal = it.avatarPreviewUrl?.takeIf { v -> v.isNotBlank() } ?: it.avatarKey?.takeIf { v -> v.isNotBlank() }
                                    try { userDataManager.updateProfileBlocking(username = it.username?.takeIf { v -> v.isNotBlank() }, firstName = it.firstName?.takeIf { v -> v.isNotBlank() }, lastName = it.lastName?.takeIf { v -> v.isNotBlank() }, email = it.email, bio = it.bio, dateOfBirth = it.dateOfBirth, avatarUrl = avatarFinal) } catch (e: Exception) { Log.w("AuthRepository", "login: immediate profile update blocking failed: ${e.message}") }
                                    try { userDataManager.updateProfileImage(avatarFinal) } catch (_: Exception) {}
                                } catch (e: Exception) { Log.w("AuthRepository", "login: processing immediate profile data failed: ${e.message}") }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("AuthRepository", "login: immediate profile fetch failed: ${e.message}")
                    }
                }

                AuthResult.Success(requiresVerification = false)
            } catch (_: IOException) {
                AuthResult.Error("Network error. Please check your internet connection.", null)
            } catch (_: Throwable) {
                AuthResult.Error("Unexpected error. Please try again.", null)
            }
        }
    }

    suspend fun prefetchAfterLogin(email: String): Result<Unit> {
        return try {
            try {
                Log.d("AuthRepository", "prefetchAfterLogin: fetching communities for $email")
                val commRes = communityRepo.fetchMyCommunitiesRemote(requesterEmail = email)
                Log.d("AuthRepository", "prefetchAfterLogin: community fetch result: $commRes")
            } catch (e: Exception) {
                Log.w("AuthRepository", "prefetchAfterLogin: fetchMyCommunitiesRemote failed: ${e.message}")
            }

            try {
                Log.d("AuthRepository", "prefetchAfterLogin: fetching profile for $email")
                val profileResp = api.getProfile(email)
                if (profileResp.isSuccessful) {
                    val envelope = profileResp.body()
                    val p = envelope?.data
                    p?.let {
                        try {
                            val avatarFinal = it.avatarPreviewUrl?.takeIf { v -> v.isNotBlank() } ?: it.avatarKey?.takeIf { v -> v.isNotBlank() }
                            try {
                                userDataManager.updateProfileBlocking(
                                    username = it.username?.takeIf { v -> v.isNotBlank() },
                                    firstName = it.firstName?.takeIf { v -> v.isNotBlank() },
                                    lastName = it.lastName?.takeIf { v -> v.isNotBlank() },
                                    email = it.email,
                                    bio = it.bio,
                                    dateOfBirth = it.dateOfBirth,
                                    location = null,
                                    website = null,
                                    avatarUrl = avatarFinal,
                                    coverPhotoUrl = null,
                                    followersCount = null,
                                    followingCount = null,
                                    isPrivate = null
                                )
                                Log.d("AuthRepository", "prefetchAfterLogin: profile update blocking complete")
                            } catch (e: Exception) {
                                Log.w("AuthRepository", "prefetchAfterLogin: profile update blocking failed: ${e.message}")
                                try { userDataManager.updateProfile(username = it.username, firstName = it.firstName, lastName = it.lastName, email = it.email, bio = it.bio, dateOfBirth = it.dateOfBirth, avatarUrl = avatarFinal) } catch (_: Exception) {}
                            }
                            try { userDataManager.updateProfileImage(avatarFinal) } catch (_: Exception) {}
                        } catch (_: Exception) {}
                    }
                } else {
                    Log.w("AuthRepository", "prefetchAfterLogin: profile fetch HTTP ${profileResp.code()}")
                }
            } catch (e: Exception) {
                Log.w("AuthRepository", "prefetchAfterLogin: profile fetch failed: ${e.message}")
            }

            Result.success(Unit)
        } catch (t: Throwable) {
            Log.w("AuthRepository", "prefetchAfterLogin: failed with ${t.message}")
            Result.failure(t)
        }
    }

    /**
     * Enqueue background prefetch (fire-and-forget). This uses a repository-owned scope so
     * the operation continues even if the caller's coroutine scope is cancelled.
     */
    fun enqueuePrefetch(email: String) {
        try {
            bgScope.launch {
                try {
                    prefetchAfterLogin(email)
                } catch (e: Exception) {
                    Log.w("AuthRepository", "enqueuePrefetch: failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w("AuthRepository", "enqueuePrefetch: schedule failed: ${e.message}")
        }
    }

    /** Return the persisted email from UserDataManager (nullable). */
    suspend fun getPersistedEmail(): String? {
        return try { userDataManager.getEmail() } catch (_: Exception) { null }
    }
}
