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

sealed class DashboardResult {
    data class Success(val username: String): DashboardResult()
    data class Error(val message: String, val status: Int? = null): DashboardResult()
}

// Small UploadResult used by helper/repository to return upload outcome
data class UploadResult(val success: Boolean, val downloadUrl: String?)

class DashboardRepository(private val context: Context) {
    private val api = NetworkModule.createApiService(context)

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
                    if (body != null && (body.status == 200 || body.status == 201)) {
                        val uname = try { body.data } catch (_: Exception) { username }
                        DashboardResult.Success(uname)
                    } else {
                        DashboardResult.Error(body?.message ?: "Username validation failed.", body?.status)
                    }
                } else {
                    val statusCode = resp.code()
                    DashboardResult.Error("Username validation failed.", statusCode)
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
                    val preview = resp.body()?.avatarPreviewUrl
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
                        if ((downloadUrl == null || downloadUrl.isBlank()) && !rawRespBodyString.isNullOrBlank()) {
                            try {
                                val regex = """https?://[\w\-./?=&%:;#@+~]+""".toRegex()
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
                        val body = resp.body()
                        val avatarUrl = body?.avatarUrl

                        val isCompleteUrl = avatarUrl?.let {
                            it.startsWith("http://", ignoreCase = true) ||
                            it.startsWith("https://", ignoreCase = true)
                        } ?: false

                        val userDataManager = UserDataManager.getInstance(context)
                        userDataManager.updateProfile(
                            username = body?.username,
                            firstName = body?.firstName,
                            lastName = body?.lastName,
                            email = body?.email,
                            bio = body?.bio,
                            dateOfBirth = body?.dateOfBirth,
                            location = body?.location,
                            website = body?.website,
                            avatarUrl = if (isCompleteUrl) avatarUrl else null,
                            coverPhotoUrl = body?.coverPhotoUrl,
                            followersCount = body?.followersCount,
                            followingCount = body?.followingCount,
                            isPrivate = body?.isPrivate
                        )
                        return@withContext true
                    } catch (_: Exception) {
                        return@withContext true
                    }
                }
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

                val body = resp.body()
                val avatarPreview = body?.avatarPreviewUrl
                val fullAvatarUrl = if (!avatarPreview.isNullOrBlank()) {
                    val s = avatarPreview.trim()
                    if (s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)) s
                    else {
                        val baseUrl = com.example.myapplication.BuildConfig.BASE_URL.trimEnd('/') + "/"
                        "${baseUrl}uploads/${s.trimStart('/')}"
                    }
                } else null

                val userDataManager = UserDataManager.getInstance(context)
                userDataManager.updateProfile(
                    username = body?.username,
                    firstName = body?.firstName,
                    lastName = body?.lastName,
                    email = body?.email,
                    bio = body?.bio,
                    dateOfBirth = body?.dateOfBirth,
                    location = body?.location,
                    website = body?.website,
                    avatarUrl = fullAvatarUrl,
                    coverPhotoUrl = body?.coverPreviewUrl,
                    followersCount = null,
                    followingCount = null,
                    isPrivate = body?.isPrivate
                )
                return@withContext true
            } catch (_: Exception) {
                return@withContext false
            }
        }
    }
}