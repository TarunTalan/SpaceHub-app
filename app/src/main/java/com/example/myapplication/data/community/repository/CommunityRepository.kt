package com.example.myapplication.data.community.repository

import android.content.Context
import com.example.myapplication.data.community.dao.CommunityDao
import com.example.myapplication.data.community.database.CommunityDatabase
import com.example.myapplication.data.community.model.*
import com.example.myapplication.data.network.NetworkModule
import com.example.myapplication.data.user.UserDataManager
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.Flow


/*
  Repository for Community data operations.
  Provides a clean API for accessing communities from Room database and remote APIs.
 */
class CommunityRepository private constructor(context: Context) {

    private val communityDao: CommunityDao = CommunityDatabase.getInstance(context).communityDao()
    private val api = NetworkModule.createApiService(context)
    private val userData = UserDataManager.getInstance(context)

    companion object {
        @Volatile
        private var INSTANCE: CommunityRepository? = null

        fun getInstance(context: Context): CommunityRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CommunityRepository(context).also { INSTANCE = it }
            }
        }
    }


    /*
      Observe all communities (auto-updates UI)
     */
    fun observeAllCommunities(): Flow<List<Community>> = communityDao.getAllCommunitiesFlow()

    // Observe specific community by ID (auto-updates UI)
    fun observeCommunityById(communityId: String): Flow<Community?> = communityDao.getCommunityByIdFlow(communityId)

    // Unified observe: My communities (both owned and joined)
    fun observeMyCommunities(): Flow<List<Community>> = communityDao.getMyCommunitiesFlow()

    // Search communities by name or description
    fun searchCommunities(query: String): Flow<List<Community>> = communityDao.searchCommunitiesFlow(query)


    // Get specific community by ID once
    suspend fun getCommunityById(communityId: String): Community? = communityDao.getCommunityById(communityId)


    // Save a new community (e.g., after creating via API)
    suspend fun saveCommunity(community: Community) { communityDao.insertCommunity(community) }

    /*
      Create community from API response
      Call this after successful createCommunity API call
     */
    suspend fun createCommunity(
        communityId: String,
        name: String,
        description: String?,
        profilePicUrl: String? = null,
        profilePicLocalPath: String? = null,
        coverPhotoUrl: String? = null,
        coverPhotoLocalPath: String? = null,
        category: String? = null,
        isPrivate: Boolean = false,
        creatorId: String? = null,
        creatorName: String? = null
    ): Community {
        val community = Community(
            communityId = communityId,
            name = name,
            description = description,
            profilePicUrl = profilePicUrl,
            profilePicLocalPath = profilePicLocalPath,
            coverPhotoUrl = coverPhotoUrl,
            coverPhotoLocalPath = coverPhotoLocalPath,
            category = category,
            isPrivate = isPrivate,
            creatorId = creatorId,
            creatorName = creatorName,
            isOwner = true,
            isMember = true,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        communityDao.insertCommunity(community)
        return community
    }


    // Update community details
    suspend fun updateCommunityDetails(communityId: String, name: String, description: String?) {
        communityDao.updateCommunityDetails(communityId, name, description)
    }

    // Update community profile picture
    suspend fun updateCommunityProfilePic(communityId: String, url: String?, localPath: String?) {
        communityDao.updateCommunityProfilePic(communityId, url, localPath)
    }

    // Update full community object
    suspend fun updateCommunity(community: Community) { communityDao.updateCommunity(community) }

    //Update member count
    suspend fun updateMemberCount(communityId: String, count: Int) { communityDao.updateMemberCount(communityId, count) }

    // Delete a community
    suspend fun deleteCommunity(communityId: String) { communityDao.deleteCommunityById(communityId) }

    // Delete all communities (e.g., on logout)
    suspend fun deleteAllCommunities() { communityDao.deleteAllCommunities() }

    // ---------------- Remote APIs ----------------

    suspend fun fetchMembers(communityId: String): Result<List<Member>> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val resp = api.getAllMembers(GetAllMembersRequest(communityId = communityId, requesterEmail = email))
            if (resp.isSuccessful) {
                val body = resp.body()
                val list = body?.data?.members ?: emptyList()
                Result.success(list)
            } else {
                Result.failure(RuntimeException("HTTP ${resp.code()}"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun changeMemberRole(communityId: String, targetUserEmail: String, newRole: String): Result<Unit> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val resp = api.changeRole(ChangeRoleRequest(communityId, newRole, email, targetUserEmail))
            if (resp.isSuccessful && resp.body()?.status in listOf(200, 201)) Result.success(Unit)
            else Result.failure(RuntimeException("Failed: ${resp.code()}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    suspend fun removeMember(communityId: String, targetUserEmail: String): Result<Unit> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val resp = api.removeMember(RemoveMemberRequest(communityId, email, targetUserEmail))
            if (resp.isSuccessful && resp.body()?.status in listOf(200, 201)) Result.success(Unit)
            else Result.failure(RuntimeException("Failed: ${resp.code()}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    suspend fun leaveCommunity(communityId: String, communityName: String): Result<Unit> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val resp = api.leaveCommunity(LeaveRequest(communityName = communityName, userEmail = email))
            if (resp.isSuccessful && resp.body()?.status in listOf(200, 201)) Result.success(Unit)
            else Result.failure(RuntimeException("Failed: ${resp.code()}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    // Rooms (per provided API definitions)
    suspend fun createRoom(communityId: String, roomName: String): Result<Unit> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val resp = api.createRoom(communityId, CreateRoomRequest(requesterEmail = email, roomName = roomName))
            if (resp.isSuccessful && (resp.body()?.status in listOf(200, 201))) Result.success(Unit)
            else Result.failure(RuntimeException("HTTP ${resp.code()}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    suspend fun deleteRoom(communityId: String, roomId: String): Result<Unit> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val resp = api.deleteRoom(communityId, roomId, email)
            if (resp.isSuccessful && (resp.body()?.status in listOf(200, 201))) Result.success(Unit)
            else Result.failure(RuntimeException("HTTP ${resp.code()}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    suspend fun getAllRooms(communityId: String): Result<List<DataRoom>> {
        return try {
            val resp = api.getAllRooms(communityId)
            if (!resp.isSuccessful) return Result.failure(RuntimeException("HTTP ${resp.code()}"))
            val body = resp.body()
            val dataEl = body?.data
            val gson = Gson()
            val rooms: List<DataRoom> = when {
                dataEl == null || dataEl.isJsonNull -> emptyList()
                dataEl.isJsonArray -> gson.fromJson(dataEl as JsonArray, Array<DataRoom>::class.java).toList()
                dataEl.isJsonObject -> {
                    val obj = dataEl as JsonObject
                    val roomsEl = obj.get("rooms")
                    if (roomsEl != null && roomsEl.isJsonArray) gson.fromJson(roomsEl as JsonArray, Array<DataRoom>::class.java).toList() else emptyList()
                }
                else -> emptyList()
            }
            Result.success(rooms)
        } catch (t: Throwable) { Result.failure(t) }
    }

    suspend fun fetchMemberCount(communityId: String): Result<Int> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val resp = api.getAllMembers(GetAllMembersRequest(communityId = communityId, requesterEmail = email))
            if (resp.isSuccessful) {
                val body = resp.body()
                val count = body?.data?.totalMembers ?: body?.data?.members?.size ?: 0
                Result.success(count)
            } else Result.failure(RuntimeException("HTTP ${resp.code()}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    suspend fun renameRoom(communityId: String, roomId: String, newName: String): Result<Unit> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val resp = api.renameRoom(
                communityId = communityId,
                roomId = roomId,
                body = RenameRoomRequest(newRoomName = newName, requesterEmail = email)
            )
            if (resp.isSuccessful && (resp.body()?.status in listOf(200, 201))) Result.success(Unit)
            else Result.failure(RuntimeException("HTTP ${resp.code()}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    suspend fun deleteCommunityRemote(communityId: String): Result<Unit> {
        return try {
            // Load the community locally to check ownership and get its name
            val community = communityDao.getCommunityById(communityId)
                ?: return Result.failure(IllegalStateException("Community not found locally"))

            // Ensure current user email is known
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))

            // Check admin/owner permission. Use isOwner when available, otherwise compare creatorId if present.
            val isAdmin = community.isOwner || (!community.creatorId.isNullOrBlank() && community.creatorId == email)
            if (!isAdmin) return Result.failure(IllegalAccessException("Only admins can delete community"))

            // Build request body per new API
            val req = DeleteCommunityRequest(name = community.name, userEmail = email)
            val resp = api.deleteCommunity(req)
            if (resp.isSuccessful && (resp.body()?.status in listOf(200, 201))) {
                // Remove locally as well
                communityDao.deleteCommunityById(communityId)
                Result.success(Unit)
            } else {
                val code = resp.code()
                val bodyMsg = resp.body()?.message ?: ""
                Result.failure(RuntimeException("HTTP $code - $bodyMsg"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun updateCommunityInfoRemote(communityId: String, name: String, description: String): Result<Unit> {
        return try {
            val resp = api.updateCommunityInfo(UpdateCommunityRequest(communityId = communityId, name = name, description = description))
            if (resp.isSuccessful && (resp.body()?.status in listOf(200, 201))) {
                communityDao.updateCommunityDetails(communityId, name, description)
                Result.success(Unit)
            } else Result.failure(RuntimeException("HTTP ${resp.code()}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    // Fetch "My communities" from remote API and persist locally into Room
    suspend fun fetchMyCommunitiesRemote(requesterEmail: String? = null): Result<Unit> {
        return try {
            // Prefer provided email (this avoids races when DataStore write is still pending).
            val email = requesterEmail ?: userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))

            val resp = api.getMyCommunities(email)
            if (!resp.isSuccessful) return Result.failure(RuntimeException("HTTP ${resp.code()}"))

            val body = resp.body()
            val data = body?.data ?: return Result.success(Unit)

            // The generated model for data contains a `communities` list named in DataXXXX
            val communitiesList = try {
                // Map network model CommunityX/DataXX etc to local Community model
                val mapped = data.communities.map { net ->
                    val bannerStr = try {
                        val s = net.bannerUrl?.toString() ?: ""
                        s.takeIf { it.isNotBlank() && it != "null" }
                    } catch (t: Throwable) { null }

                    val img = net.imageUrl.takeIf { it.isNotBlank() }

                    Community(
                        communityId = net.communityId,
                        name = net.name,
                        description = net.description.takeIf { it.isNotBlank() },
                        profilePicUrl = img,
                        profilePicLocalPath = null,
                        coverPhotoUrl = bannerStr,
                        coverPhotoLocalPath = null,
                        category = null,
                        isPrivate = false,
                        creatorId = null,
                        creatorName = null,
                        isOwner = false,
                        isMember = true,
                        memberCount = 0,
                        postCount = 0,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                }
                mapped
            } catch (t: Throwable) {
                emptyList<Community>()
            }

            if (communitiesList.isNotEmpty()) {
                communityDao.insertCommunities(communitiesList)
            }

            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

}
