package com.example.myapplication.data.friends.repository

import android.content.Context
import com.example.myapplication.data.friends.model.*
import com.example.myapplication.data.friends.model.UserSearchResult
import com.example.myapplication.data.network.NetworkModule
import com.example.myapplication.data.user.UserDataManager

// Type aliases for response data
typealias Friend = Any
typealias IncomingRequest = Any

class FriendsRepository private constructor(private val context: Context) {

    private val api = NetworkModule.createApiService(context)
    private val userData = UserDataManager.getInstance(context)

    companion object {
        @Volatile
        private var INSTANCE: FriendsRepository? = null

        fun getInstance(context: Context): FriendsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FriendsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    suspend fun searchUsers(query: String, page: Int = 0, size: Int = 20): Result<List<UserSearchResult>> {
        return try {
            val response = api.searchUsers(query, page, size)

            if (response.isSuccessful && response.body()?.status in listOf(200, 201)) {
                val contentList = response.body()?.data?.content ?: emptyList()
                val users = contentList.map { UserSearchResult.fromContent(it) }
                Result.success(users)
            } else {
                Result.failure(RuntimeException(response.body()?.message ?: "Search failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendFriendRequest(friendEmail: String): Result<Unit> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val request = SendFriendRequest(friendEmail = friendEmail, userEmail = email)
            val response = api.sendFriendRequest(request)

            if (response.isSuccessful && response.body()?.status in listOf(200, 201)) {
                Result.success(Unit)
            } else {
                Result.failure(RuntimeException(response.body()?.message ?: "Failed to send friend request"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFriendsList(): Result<List<Friend>> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val request = FriendsListRequest(userEmail = email)
            val response = api.getFriendsList(request)

            if (response.isSuccessful && response.body()?.status in listOf(200, 201)) {
                val friends = response.body()?.data ?: emptyList()
                Result.success(friends)
            } else {
                Result.failure(RuntimeException(response.body()?.message ?: "Failed to get friends"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getIncomingRequests(): Result<List<IncomingRequest>> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val request = IncomingFriendRequest(userEmail = email)
            val response = api.getIncomingFriendRequests(request)

            if (response.isSuccessful && response.body()?.status in listOf(200, 201)) {
                val requests = response.body()?.data ?: emptyList()
                Result.success(requests)
            } else {
                Result.failure(RuntimeException(response.body()?.message ?: "Failed to get requests"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun respondToFriendRequest(requesterEmail: String, accept: Boolean): Result<Unit> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val request = RespondFriendRequest(
                requesterEmail = requesterEmail,
                userEmail = email,
                accept = if (accept) "true" else "false"
            )
            val response = api.respondFriendRequest(request)

            if (response.isSuccessful && response.body()?.status in listOf(200, 201)) {
                Result.success(Unit)
            } else {
                Result.failure(RuntimeException(response.body()?.message ?: "Failed to respond"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

