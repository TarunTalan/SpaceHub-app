package com.example.myapplication.data.search

import android.content.Context
import com.example.myapplication.data.network.NetworkModule
import com.example.myapplication.data.user.UserDataManager
import com.example.myapplication.ui.dashboard.adapter.CommunityUi

class SearchRepository private constructor(context: Context) {
    private val api = NetworkModule.createApiService(context)
    private val userData = UserDataManager.getInstance(context)

    companion object {
        @Volatile private var INSTANCE: SearchRepository? = null
        fun getInstance(context: Context): SearchRepository = INSTANCE ?: synchronized(this) {
            INSTANCE ?: SearchRepository(context.applicationContext).also { INSTANCE = it }
        }
    }

    suspend fun searchCommunities(query: String, page: Int = 0, size: Int = 20): Result<List<CommunityUi>> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val resp = api.searchCommunities(q = query, requesterEmail = email, page = page, size = size)
            if (!resp.isSuccessful) return Result.failure(RuntimeException("HTTP ${resp.code()}"))
            val body = resp.body()
            val list = body?.data?.communities?.map { net ->
                CommunityUi(
                    communityId = net.communityId,
                    id = net.communityId.hashCode(),
                    name = net.name,
                    imageUrl = net.imageUrl.takeIf { it.isNotBlank() },
                    subtitle = net.description,
                    isLocal = false,
                    isMember = net.isMember,
                    isOwner = false,
                    isAdmin = false
                )
            } ?: emptyList()

            // Reorder so communities where user is member/owner/admin appear first (preserve relative order)
            val (myComms, others) = list.partition { it.isMember || it.isOwner || it.isAdmin }
            val ordered = myComms + others

            Result.success(ordered)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
