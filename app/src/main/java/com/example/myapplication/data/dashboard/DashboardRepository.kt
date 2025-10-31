package com.example.myapplication.data.dashboard

import android.content.Context
import android.net.Uri
import com.example.myapplication.data.network.NetworkModule
import com.example.myapplication.data.network.ResponseParser
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
                    DashboardResult.Error(ResponseParser.parseError(resp.errorBody()), statusCode)
                }
            }
        )
    }

    // Upload profile/community image and return download URL if available
    suspend fun uploadProfileImage(email: String?, imgPath: String?, contentUri: Uri?): UploadResult {
        return withContext(Dispatchers.IO) {
            try {
                val emailRb = (email ?: "").toRequestBody("text/plain".toMediaTypeOrNull())
                val imageUriRb = (imgPath ?: "").toRequestBody("text/plain".toMediaTypeOrNull())

                val filePart = try {
                    if (!imgPath.isNullOrBlank()) {
                        val f = File(imgPath)
                        if (f.exists()) {
                            val mimeFromResolver = try { contentUri?.let { context.contentResolver.getType(it) } } catch (_: Exception) { null }
                            val ext = f.extension.takeIf { it.isNotBlank() } ?: android.webkit.MimeTypeMap.getFileExtensionFromUrl(f.absolutePath)
                            val mimeFromExt = ext?.lowercase()?.let { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
                            val mime = mimeFromResolver ?: mimeFromExt ?: "application/octet-stream"
                            val rejectNonImage = true
                            if (rejectNonImage && !mime.startsWith("image/")) {
                                null
                            } else {
                                val req = f.asRequestBody(mime.toMediaTypeOrNull())
                                MultipartBody.Part.createFormData("image", f.name, req)
                            }
                        } else null
                    } else null
                } catch (_: Exception) { null }

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
}