package com.example.myapplication.data.groups.repository

import android.content.Context
import com.example.myapplication.data.community.model.RequestJoinRequest
import com.example.myapplication.data.community.model.RequestJoinResponse
import com.example.myapplication.data.groups.model.*
import com.example.myapplication.data.network.NetworkModule
import com.example.myapplication.data.user.UserDataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull

/**
 * Repository for Local Group APIs. Mirrors CommunityRepository but without Room persistence.
 */
class LocalGroupRepository private constructor(private val context: Context) {
    private val api = NetworkModule.createApiService(context)
    private val userData = UserDataManager.getInstance(context)

    companion object {
        @Volatile
        private var INSTANCE: LocalGroupRepository? = null

        fun getInstance(context: Context): LocalGroupRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LocalGroupRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    suspend fun getAllLocalGroups(): Result<List<DataX>> = withContext(Dispatchers.IO) {
        try {
            val email = userData.getEmail() ?: return@withContext Result.failure(IllegalStateException("Email not set"))
            val resp = api.getAllLocalGroups(email)
            if (!resp.isSuccessful) return@withContext Result.failure(RuntimeException("HTTP ${resp.code()}"))
            val body = resp.body()
            val list = body?.data ?: emptyList()
            Result.success(list)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun getLocalGroupDetails(groupId: String): Result<DataXX> = withContext(Dispatchers.IO) {
        try {
            val resp = api.getLocalGroupDetails(groupId)
            if (!resp.isSuccessful) return@withContext Result.failure(RuntimeException("HTTP ${resp.code()}"))
            val body = resp.body()
            val data = body?.data ?: return@withContext Result.failure(RuntimeException("Empty body"))
            Result.success(data)
        } catch (t: Throwable) { Result.failure(t) }
    }

    suspend fun createLocalGroup(
        name: RequestBody,
        description: RequestBody,
        imageFile: MultipartBody.Part? = null
    ): Result<CreateLocalGroupResponse> = withContext(Dispatchers.IO) {
        try {
            val email = userData.getEmail() ?: return@withContext Result.failure(IllegalStateException("Email not set"))
            val creatorEmail = email.toRequestBody("text/plain".toMediaTypeOrNull())
            val resp = api.createLocalGroup(name = name, description = description, creatorEmail = creatorEmail, imageFile = imageFile)
            if (!resp.isSuccessful) return@withContext Result.failure(RuntimeException("HTTP ${resp.code()}"))
            val body = resp.body() ?: return@withContext Result.failure(RuntimeException("Empty response body"))
            Result.success(body)
        } catch (t: Throwable) { Result.failure(t) }
    }

    suspend fun deleteLocalGroup(groupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val email = userData.getEmail() ?: return@withContext Result.failure(IllegalStateException("Email not set"))
            val req = DeleteLocalGroupRequest(groupId = groupId, requesterEmail = email)
            val resp = api.deleteLocalGroup(req)
            if (resp.isSuccessful && resp.body()?.status in listOf(200, 201)) Result.success(Unit)
            else Result.failure(RuntimeException("HTTP ${resp.code()} - ${resp.errorBody()?.string()}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    suspend fun getLocalGroupMembers(localGroupId: String): Result<List<DataXXX>> = withContext(Dispatchers.IO) {
        try {
            val resp = api.getLocalGroupMembers(localGroupId)
            if (!resp.isSuccessful) return@withContext Result.failure(RuntimeException("HTTP ${resp.code()}"))
            val body = resp.body()
            val list: List<DataXXX> = (body?.data as? List<DataXXX>) ?: emptyList()
            Result.success(list)
        } catch (t: Throwable) { Result.failure(t) }
    }

    suspend fun updateLocalGroupSettings(
        localGroupId: String,
        requesterEmailBody: RequestBody,
        nameBody: RequestBody,
        imageFile: MultipartBody.Part? = null
    ): Result<UpdateLocalGroupProfileResponse> = withContext(Dispatchers.IO) {
        try {
            val resp = api.updateLocalGroupSettings(localGroupId = localGroupId, requesterEmail = requesterEmailBody, name = nameBody, imageFile = imageFile)
            if (!resp.isSuccessful) return@withContext Result.failure(RuntimeException("HTTP ${resp.code()}"))
            val body = resp.body() ?: return@withContext Result.failure(RuntimeException("Empty response"))
            Result.success(body)
        } catch (t: Throwable) { Result.failure(t) }
    }

    suspend fun requestToJoinLocalGroup(groupId: String): Result<RequestJoinResponse> = withContext(Dispatchers.IO) {
        try {
            val email = userData.getEmail() ?: return@withContext Result.failure(IllegalStateException("Email not set"))
            val req = RequestJoinRequest(communityName = groupId, userEmail = email)
            val resp = api.requestToJoinLocalGroup(req)
            if (!resp.isSuccessful) return@withContext Result.failure(RuntimeException("HTTP ${resp.code()}"))
            val body = resp.body() ?: return@withContext Result.failure(RuntimeException("Empty response"))
            Result.success(body)
        } catch (t: Throwable) { Result.failure(t) }
    }
}
