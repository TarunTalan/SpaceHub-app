package com.example.myapplication.data.dashboard

import android.content.Context
import android.net.Uri
import com.example.myapplication.data.network.NetworkModule
import com.example.myapplication.data.dashboard.model.UsernameRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.File
import java.io.IOException

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

    // Helper: get stored email from SharedPreferences as string
    private fun getEmailString(): String {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getString("email", null) ?: prefs.getString("user_email", null) ?: ""
    }

    // Helper: create MultipartBody.Part from file path, validating mime type and contentUri when available
    private fun createFilePart(partName: String, imgPath: String?, contentUri: Uri?): MultipartBody.Part? {
        try {
            if (imgPath.isNullOrBlank()) return null
            val f = File(imgPath)
            if (!f.exists()) return null

            val mimeFromResolver = try { contentUri?.let { context.contentResolver.getType(it) } } catch (_: Exception) { null }
            val ext = f.extension.takeIf { it.isNotBlank() } ?: android.webkit.MimeTypeMap.getFileExtensionFromUrl(f.absolutePath)
            val mimeFromExt = ext?.lowercase()?.let { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            val mime = mimeFromResolver ?: mimeFromExt ?: "application/octet-stream"

            val rejectNonImage = true
            if (rejectNonImage && !mime.startsWith("image/")) {
                return null
            }

            val req = f.asRequestBody(mime.toMediaTypeOrNull())
            return MultipartBody.Part.createFormData(partName, f.name, req)
        } catch (_: Exception) {
            return null
        }
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
                                val regex = "https?://[\\w\\-./?=&%:;#@+~]+".toRegex()
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
    suspend fun updateProfilePic(imgPath: String?, contentUri: Uri?): UploadResult {
        return withContext(Dispatchers.IO) {
            try {
                val emailStr = getEmailString()
                val filePart = createFilePart("file", imgPath, contentUri)
                val resp = api.updateProfilePic(emailStr, filePart)

                if (resp.isSuccessful) {
                    try {
                        val body = resp.body()
                        val avatarUrl = try { body?.avatarUrl } catch (_: Exception) { null }

                        // Use UserDataManager for centralized data persistence
                        val userDataManager = com.example.myapplication.data.user.UserDataManager.getInstance(context)

                        // Only save URL if it's a complete URL (starts with http/https)
                        // Otherwise, keep local path as the source since file exists locally
                        val isCompleteUrl = avatarUrl?.let {
                            it.startsWith("http://", ignoreCase = true) ||
                            it.startsWith("https://", ignoreCase = true)
                        } ?: false

                        if (isCompleteUrl) {
                            // Server returned a real URL - save it and clear local path
                            userDataManager.updateProfileImage(
                                url = avatarUrl,
                                localPath = null,
                                contentUri = null
                            )
                        } else {
                            // Server returned relative path or nothing - keep local file as source
                            userDataManager.updateProfileImage(
                                url = null,
                                localPath = imgPath,
                                contentUri = contentUri
                            )
                        }

                        return@withContext UploadResult(true, avatarUrl)
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

                        // Only use avatarUrl if it's a complete URL (starts with http/https)
                        // Otherwise, don't update profile image (keep existing local/server image)
                        val isCompleteUrl = avatarUrl?.let {
                            it.startsWith("http://", ignoreCase = true) ||
                            it.startsWith("https://", ignoreCase = true)
                        } ?: false

                        // Use UserDataManager for centralized data persistence
                        val userDataManager = com.example.myapplication.data.user.UserDataManager.getInstance(context)
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
}