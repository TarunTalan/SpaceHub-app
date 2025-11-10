package com.example.myapplication.data.dashboard

import android.content.Context
import android.net.Uri
import com.example.myapplication.data.network.NetworkModule
import com.example.myapplication.data.dashboard.model.UsernameRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.File
import java.io.IOException
import com.example.myapplication.data.user.UserDataManager
import com.example.myapplication.data.dashboard.model.GetProfileResponse

sealed class DashboardResult {
    data class Success(val username: String): DashboardResult()
    data class Error(val message: String, val status: Int? = null): DashboardResult()
}

// Small UploadResult used by helper/repository to return upload outcome
data class UploadResult(val success: Boolean, val downloadUrl: String?)

class DashboardRepository(private val context: Context) {
    private val api = NetworkModule.createApiService(context)
    private val TAG = "DashboardRepo"

    private suspend inline fun <T> safeApiCall(
        crossinline call: suspend () -> Response<T>,
        crossinline handle: (Response<T>) -> DashboardResult
    ): DashboardResult = withContext(Dispatchers.IO) {
        try {
            val resp = call()
            handle(resp)
        } catch (_: IOException) {
            DashboardResult.Error("Network error. Please check your internet connection.")
        } catch (_: Exception) {
            DashboardResult.Error("Unexpected error. Please try again.")
        }
    }

    suspend fun validateUsername(email: String, username: String): DashboardResult {
        return safeApiCall(
            call = { api.validateUsername(UsernameRequest(email, username)) },
            handle = { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body()
                    val statusCode = body?.status ?: resp.code()
                    if (statusCode == 200 || statusCode == 201) {
                        val uname = body?.data ?: username
                        DashboardResult.Success(uname)
                    } else {
                        DashboardResult.Error(body?.message ?: "Username validation failed.", statusCode)
                    }
                } else {
                    DashboardResult.Error("Username validation failed.", resp.code())
                }
            }
        )
    }

    // Helper: get stored email, prefer DataStore, fallback to SharedPreferences
    private suspend fun getEmailString(): String {
        val udm = UserDataManager.getInstance(context)
        val dsEmail = runCatching { udm.getEmail() }.getOrNull()
        if (!dsEmail.isNullOrBlank()) return dsEmail
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getString("email", null) ?: prefs.getString("user_email", null) ?: ""
    }

    // Helper: create MultipartBody.Part from file path, validating mime type and contentUri when available
    private fun createFilePart(partName: String, imgPath: String?, contentUri: Uri?): MultipartBody.Part? {
        try {
            if (!imgPath.isNullOrBlank()) {
                val f = File(imgPath)
                if (f.exists()) {
                    val mimeFromResolver = try { contentUri?.let { context.contentResolver.getType(it) } } catch (_: Exception) { null }
                    val ext = f.extension.takeIf { it.isNotBlank() } ?: android.webkit.MimeTypeMap.getFileExtensionFromUrl(f.absolutePath)
                    val mimeFromExt = ext?.lowercase()?.let { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
                    val mime = mimeFromResolver ?: mimeFromExt ?: "application/octet-stream"
                    val req = f.asRequestBody(mime.toMediaTypeOrNull())
                    return MultipartBody.Part.createFormData(partName, f.name, req)
                }
            }
            return null
        } catch (_: Exception) {
            return null
        }
    }

    private fun createPartFromBytes(partName: String, fileName: String, bytes: ByteArray, mime: String? = null): MultipartBody.Part {
        val mediaType = (mime ?: guessMimeFromName(fileName) ?: "image/png").toMediaTypeOrNull()
        val body: RequestBody = bytes.toRequestBody(mediaType)
        return MultipartBody.Part.createFormData(partName, fileName, body)
    }

    private fun createPartFromContentUri(partName: String, contentUri: Uri): MultipartBody.Part? {
        return try {
            val cr = context.contentResolver
            val mime: String? = try { cr.getType(contentUri) } catch (_: Exception) { null }
            val name = runCatching {
                var result: String? = null
                cr.query(contentUri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) result = c.getString(idx)
                }
                result
            }.getOrNull() ?: "upload_${System.currentTimeMillis()}"

            val bytes = cr.openInputStream(contentUri)?.use { it.readBytes() } ?: return null
            createPartFromBytes(partName, name, bytes, mime)
        } catch (_: Exception) { null }
    }

    private fun guessMimeFromName(name: String): String? {
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> null
        }
    }

    // Helper to resolve avatar URL: if value is full URL return as-is, otherwise try to fetch preview and fallback to constructed uploads URL
    private suspend fun resolveAvatarUrl(email: String, avatarPathOrUrl: String?): String? {
        if (avatarPathOrUrl.isNullOrBlank()) return null
        val s = avatarPathOrUrl.trim()
        if (s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)) return s

        val uploadsBase = com.example.myapplication.BuildConfig.BASE_URL.trimEnd('/') + "/uploads/"

        // Try to obtain preview URL from server with a few retries; if not available, construct uploads URL
        repeat(3) {
            try {
                val resp = api.getProfile(email)
                if (resp.isSuccessful) {
                    val envelope = resp.body()
                    val preview: String? = envelope?.data?.avatarPreviewUrl
                    if (!preview.isNullOrBlank()) return preview
                }
            } catch (_: Exception) {
                // ignore and retry
            }
            // small backoff
            delay(300L)
        }

        return "$uploadsBase${s.trimStart('/')}"
    }

    // Upload profile/community image and return download URL if available
    suspend fun uploadProfileImage(email: String?, imgPath: String?, contentUri: Uri?): UploadResult {
        return withContext(Dispatchers.IO) {
            try {
                val emailRb = (email ?: "").toRequestBody("text/plain".toMediaTypeOrNull())
                val imageUriRb = (imgPath ?: "").toRequestBody("text/plain".toMediaTypeOrNull())

                val filePart = createFilePart("image", imgPath, contentUri)

                val resp = api.uploadProfile(emailRb, imageUriRb, filePart)
                if (resp.isSuccessful) {
                    try {
                        val body = resp.body()
                        var downloadUrl = body?.data
                        val rawRespBodyString: String? = try { try { resp.raw().peekBody(1024 * 1024).string() } catch (_: Exception) { null } } catch (_: Exception) { null }
                        if (downloadUrl.isNullOrBlank() && !rawRespBodyString.isNullOrBlank()) {
                            try {
                                val regex = """https?://[\w./?=&%:;#@+~-]+""".toRegex()
                                val m = regex.find(rawRespBodyString)
                                if (m != null) downloadUrl = m.value
                            } catch (_: Exception) {}
                        }
                        return@withContext UploadResult(true, downloadUrl)
                    } catch (_: Exception) {
                        return@withContext UploadResult(true, null)
                    }
                } else {
                    return@withContext UploadResult(false, null)
                }
            } catch (_: Exception) {
                return@withContext UploadResult(false, null)
            }
        }
    }

    // New method: call profile/avatar endpoint which returns full user object including avatarUrl
    suspend fun updateProfilePic(
        imgPath: String? = null,
        contentUri: Uri? = null,
        bytes: ByteArray? = null,
        filename: String? = null
    ): UploadResult {
        return withContext(Dispatchers.IO) {
            try {
                val emailStr = getEmailString()

                val filePart: MultipartBody.Part? = when {
                    bytes != null -> createPartFromBytes("file", filename ?: "profile.png", bytes)
                    contentUri != null -> createPartFromContentUri("file", contentUri)
                    else -> createFilePart("file", imgPath, null)
                }

                val resp = api.updateProfilePic(emailStr, filePart)
                if (!resp.isSuccessful) return@withContext UploadResult(false, null)

                val avatarPathOrUrl = resp.body()?.avatarUrl
                if (avatarPathOrUrl.isNullOrBlank()) return@withContext UploadResult(true, null)

                val finalUrl = resolveAvatarUrl(emailStr, avatarPathOrUrl)
                if (!finalUrl.isNullOrBlank()) {
                    val userDataManager = UserDataManager.getInstance(context)
                    userDataManager.updateProfileImage(finalUrl)
                }

                return@withContext UploadResult(true, finalUrl)
            } catch (_: Exception) {
                return@withContext UploadResult(false, null)
            }
        }
    }

    // Update full profile via API; returns true on success
    suspend fun updateProfile(request: com.example.myapplication.data.dashboard.model.UpdateProfileRequest): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val emailStr = getEmailString()
                val resp = api.updateProfile(emailStr, request)
                if (resp.isSuccessful) {
                    try {
                        // New API shape: UpdateProfileResponse wraps profile under `data`
                        val envelope = resp.body()
                        val profile = envelope?.data

                        val avatarKey = profile?.avatarKey
                        val coverPhotoUrl: String? = null // not present in ProfileData
                        android.util.Log.d(TAG, "updateProfile: resp.profile.avatarKey=$avatarKey, coverPhotoUrl=$coverPhotoUrl")

                        // Resolve relative avatar keys to a usable finalUrl (handles signed URLs and preview URLs)
                        val userDataManager = UserDataManager.getInstance(context)
                        val finalAvatarUrl: String? = try {
                            if (avatarKey.isNullOrBlank()) {
                                null
                            } else if (avatarKey.startsWith("http://", ignoreCase = true) || avatarKey.startsWith("https://", ignoreCase = true)) {
                                avatarKey
                            } else {
                                val ownerEmail = profile?.email ?: emailStr
                                resolveAvatarUrl(ownerEmail, avatarKey) ?: (com.example.myapplication.BuildConfig.BASE_URL.trimEnd('/') + "/" + avatarKey.trimStart('/'))
                            }
                        } catch (e: Exception) {
                            android.util.Log.w(TAG, "Failed to resolve avatar url: ${e.message}")
                            null
                        }

                        android.util.Log.d(TAG, "updateProfile: finalAvatarUrl=$finalAvatarUrl")
                        userDataManager.updateProfile(
                            username = profile?.username,
                            firstName = profile?.firstName,
                            lastName = profile?.lastName,
                            email = profile?.email,
                            bio = profile?.bio,
                            dateOfBirth = profile?.dateOfBirth,
                            location = null,
                            website = null,
                            avatarUrl = finalAvatarUrl,
                            coverPhotoUrl = coverPhotoUrl,
                            followersCount = null,
                            followingCount = null,
                            isPrivate = null
                        )
                        // Also store into app_prefs for backward-compatible UIs
                        try {
                            finalAvatarUrl?.let { url ->
                                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                prefs.edit().putString("uploaded_profile_url", url).apply()
                            }
                        } catch (_: Exception) {}

                        // API call succeeded; return true
                        return@withContext true
                    } catch (_: Exception) {
                        // If parsing or persisting failed, still treat API success as true
                        return@withContext true
                    }
                }
                // resp was not successful
                return@withContext false
            } catch (_: Exception) {
                return@withContext false
            }
        }
    }

    // Fetch user profile from server and save to UserDataManager
    suspend fun getProfile(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val emailStr = getEmailString()
                val resp = api.getProfile(emailStr)
                if (!resp.isSuccessful) return@withContext false

                // The API returns an envelope: { status, message, data }
                val envelope = resp.body()
                // Also log raw response for debugging parsing issues
                val rawBodyString: String? = try { resp.raw().peekBody(1024 * 1024).string() } catch (_: Exception) { null }
                try { android.util.Log.d(TAG, "getProfile: rawResponse=$rawBodyString") } catch (_: Exception) {}

                // If Retrofit's parsing produced a null .data (some responses may vary), attempt a Gson fallback
                var body: GetProfileResponse? = envelope?.data
                if (body == null && !rawBodyString.isNullOrBlank()) {
                    try {
                        val gson = com.google.gson.Gson()
                        val env = gson.fromJson(rawBodyString, com.example.myapplication.data.dashboard.model.GetProfileEnvelope::class.java)
                        body = env?.data
                        try { android.util.Log.d(TAG, "getProfile: parsed via gson fallback: profile.firstName=${body?.firstName} profile.lastName=${body?.lastName}") } catch (_: Exception) {}
                    } catch (_: Exception) {
                        // ignore
                    }
                } else {
                    try { android.util.Log.d(TAG, "getProfile: envelope.status=${envelope?.status} envelope.message=${envelope?.message} profile.firstName=${body?.firstName} profile.lastName=${body?.lastName} avatarPreviewUrl=${body?.avatarPreviewUrl} avatarKey=${body?.avatarKey}") } catch (_: Exception) {}
                }

                // If parsing failed and body is null, do not overwrite DataStore with null values
                if (body == null) {
                    try { android.util.Log.w(TAG, "getProfile: parsed profile is null - skipping DataStore update") } catch (_: Exception) {}
                    return@withContext true
                }

                // avatarPreviewUrl may be a signed full URL; avatarKey is a storage key
                val avatarPreview = body.avatarPreviewUrl?.takeIf { it.isNotBlank() }
                val avatarKey = body.avatarKey?.takeIf { it.isNotBlank() }
                val base = com.example.myapplication.BuildConfig.BASE_URL.trimEnd('/')
                val fullAvatarUrl = when {
                    avatarPreview != null -> {
                        val s = avatarPreview.trim()
                        if (s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)) s
                        else "$base/uploads/${s.trimStart('/')}"
                    }
                    avatarKey != null -> "$base/uploads/${avatarKey.trimStart('/')}"
                    else -> null
                }

                val userDataManager = UserDataManager.getInstance(context)
                try {
                    // Update DataStore synchronously from IO coroutine so collectors see the change before we return
                    userDataManager.updateProfileBlocking(
                        username = body.username,
                        firstName = body.firstName,
                        lastName = body.lastName,
                        email = body.email,
                        bio = body.bio?.toString(),
                        dateOfBirth = body.dateOfBirth?.toString(),
                        avatarUrl = fullAvatarUrl
                    )
                } catch (_: Exception) {
                    // Fallback to async update if blocking call fails
                    userDataManager.updateProfile(
                        username = body.username,
                        firstName = body.firstName,
                        lastName = body.lastName,
                        email = body.email,
                        bio = body.bio?.toString(),
                        dateOfBirth = body.dateOfBirth?.toString(),
                        avatarUrl = fullAvatarUrl
                    )
                }

                try {
                    fullAvatarUrl?.let { url ->
                        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("uploaded_profile_url", url).apply()
                    }
                } catch (_: Exception) {}

                return@withContext true
            } catch (_: Exception) {
                return@withContext false
            }
        }
    }
}
