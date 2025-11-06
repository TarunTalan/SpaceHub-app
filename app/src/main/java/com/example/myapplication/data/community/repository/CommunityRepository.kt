package com.example.myapplication.data.community.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.example.myapplication.data.community.dao.CommunityDao
import com.example.myapplication.data.community.database.CommunityDatabase
import com.example.myapplication.data.community.model.*
import com.example.myapplication.data.network.NetworkModule
import com.example.myapplication.data.user.UserDataManager
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext


/*
  Repository for Community data operations.
  Provides a clean API for accessing communities from Room database and remote APIs.
 */
class CommunityRepository private constructor(private val context: Context) {

    private val communityDao: CommunityDao = CommunityDatabase.getInstance(context).communityDao()
    private val api = NetworkModule.createApiService(context)
    private val userData = UserDataManager.getInstance(context)
    private val roomDao = CommunityDatabase.getInstance(context).roomDao()

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

    //Update member count
    suspend fun updateMemberCount(communityId: String, count: Int) { communityDao.updateMemberCount(communityId, count) }

    // Delete a community
    suspend fun deleteCommunity(communityId: String) { communityDao.deleteCommunityById(communityId) }

    // Delete all communities (e.g., on logout)
    suspend fun deleteAllCommunities() { communityDao.deleteAllCommunities() }

    suspend fun fetchMembers(communityId: String): Result<List<Member>> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val resp = api.getAllMembers(GetAllMembersRequest(communityId = communityId, requesterEmail = email))
            if (resp.isSuccessful) {
                val body = resp.body()
                val list = body?.data?.members ?: emptyList()
                // Update relationship flags locally based on my role (handle synonyms)
                val myRole = list.firstOrNull { it.email.equals(email, true) }?.role?.trim()?.uppercase()
                val isOwner = myRole == "OWNER" || myRole == "CREATOR"
                val isModerator = when {
                    myRole == null -> false
                    myRole.contains("ADMIN") -> true // ADMIN or ADMINISTRATOR
                    myRole == "MODERATOR" || myRole == "MANAGER" || myRole == "OWNER" || myRole == "CREATOR" -> true
                    else -> false
                }
                val isMember = list.any { it.email.equals(email, true) }
                runCatching { communityDao.updateRelationship(communityId, isOwner, isMember, isModerator) }
                Result.success(list)
            } else {
                Result.failure(RuntimeException("HTTP ${resp.code()}"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun changeMemberRole(communityId: String, targetUserEmail: String?, newRole: String): Result<Unit> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val resp = api.changeRole(ChangeRoleRequest(communityId, newRole, email, targetUserEmail))
            if (resp.isSuccessful && resp.body()?.status in listOf(200, 201)) Result.success(Unit)
            else Result.failure(RuntimeException("Failed: ${resp.code()}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    suspend fun removeMember(communityId: String, targetUserEmail: String?): Result<Unit> {
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
            val resp = api.leaveCommunity(LeaveCommunityRequest(communityName = communityName, userEmail = email))
            if (resp.isSuccessful && resp.body()?.status in listOf(200, 201)) {
                // Minimize API calls: update local DB immediately instead of refetching
                try { communityDao.deleteCommunityById(communityId) } catch (_: Exception) {}
                Result.success(Unit)
            } else Result.failure(RuntimeException("Failed: ${resp.code()}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    // Rooms (per provided API definitions)
    suspend fun createRoom(communityId: String, roomName: String): Result<Unit> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val resp = api.createRoom(communityId, CreateRoomRequest(requesterEmail = email, roomName = roomName))
            if (resp.isSuccessful && (resp.body()?.status in listOf(200, 201))) {
                // Refresh only this community’s rooms so UI updates; one lightweight API call
                runCatching { refreshRooms(communityId) }
                Result.success(Unit)
            } else Result.failure(RuntimeException("HTTP ${resp.code()}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    // Create a chat room under an existing room
    suspend fun createChatRoom(communityId: String, roomId: String, chatRoomName: String): Result<DataChatRoom> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            // TODO: Store and use actual userId from profile instead of email
            val req = CreateChatRoomRequest(name = chatRoomName, userId = email)
            val resp = api.createChatRoom(req)
            if (resp.isSuccessful && (resp.body()?.status in listOf(200, 201))) {
                val created = resp.body()!!.data
                Result.success(created)
            } else {
                Result.failure(RuntimeException(resp.body()?.message ?: "HTTP ${resp.code()}"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun deleteRoom(communityId: String, roomId: String): Result<Unit> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val resp = api.deleteRoom(communityId, roomId, email)
            if (resp.isSuccessful && (resp.body()?.status in listOf(200, 201))) {
                // Refresh only this community’s rooms
                runCatching { refreshRooms(communityId) }
                Result.success(Unit)
            } else Result.failure(RuntimeException("HTTP ${resp.code()}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    suspend fun getAllRooms(communityId: String): Result<List<DataRoom>> {
        return try {
            val resp = api.getAllRooms(communityId)
             if (!resp.isSuccessful) return Result.failure(RuntimeException("HTTP ${resp.code()}"))
             val body = resp.body()
             try {
                 Log.d("CommunityRepo", "getAllRooms - response code=${resp.code()} bodyData=${body?.data}")
             } catch (_: Exception) {}
             val dataEl = body?.data

             fun extractArray(el: com.google.gson.JsonElement?): com.google.gson.JsonArray? {
                 if (el == null || el.isJsonNull) return null
                 if (el.isJsonArray) return el.asJsonArray
                 if (el.isJsonObject) {
                     val obj = el.asJsonObject
                     val keys = arrayOf("rooms", "chatRooms", "data", "content", "results", "items")
                     for (k in keys) {
                         if (obj.has(k)) {
                             val child = obj.get(k)
                             val asArr = extractArray(child)
                             if (asArr != null) return asArr
                         }
                     }
                 }
                 return null
             }

             fun mapRoom(el: com.google.gson.JsonElement): DataRoom? {
                 if (el.isJsonPrimitive) {
                     // simple string or number representing room name/id
                     val v = el.asJsonPrimitive
                     val s = if (v.isString) v.asString else v.toString()
                     return DataRoom(id = s, name = s, roomCode = "")
                 }
                 if (!el.isJsonObject) return null
                 val o = el.asJsonObject
                 val id = when {
                     o.has("id") && !o.get("id").isJsonNull -> o.get("id").asString
                     o.has("_id") && !o.get("_id").isJsonNull -> o.get("_id").asString
                     o.has("roomId") && !o.get("roomId").isJsonNull -> o.get("roomId").asString
                     else -> null
                 } ?: return null
                 val name = when {
                     o.has("name") && !o.get("name").isJsonNull -> o.get("name").asString
                     o.has("roomName") && !o.get("roomName").isJsonNull -> o.get("roomName").asString
                     o.has("title") && !o.get("title").isJsonNull -> o.get("title").asString
                     else -> id
                 }
                 val code = when {
                     o.has("roomCode") && !o.get("roomCode").isJsonNull -> o.get("roomCode").asString
                     o.has("room_code") && !o.get("room_code").isJsonNull -> o.get("room_code").asString
                     o.has("type") && !o.get("type").isJsonNull -> o.get("type").asString
                     o.has("code") && !o.get("code").isJsonNull -> o.get("code").asString
                     else -> ""
                 }
                 return DataRoom(id = id, name = name, roomCode = code)
             }

             val arr = extractArray(dataEl)
             val rooms: List<DataRoom> = arr?.mapNotNull { mapRoom(it) } ?: emptyList()
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
            if (resp.isSuccessful && (resp.body()?.status in listOf(200, 201))) {
                // Refresh only this community’s rooms
                runCatching { refreshRooms(communityId) }
                Result.success(Unit)
            } else Result.failure(RuntimeException("HTTP ${resp.code()}"))
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
                // Remove locally as well (immediate UI feedback)
                communityDao.deleteCommunityById(communityId)
                // Then reconcile with server to ensure no stale entries remain
                runCatching { fetchMyCommunitiesRemote(email) }
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
            val email = requesterEmail ?: userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val resp = api.getMyCommunities(email)
            if (!resp.isSuccessful) return Result.failure(RuntimeException("HTTP ${resp.code()}"))

            val body = resp.body()
            val data = body?.data ?: return Result.success(Unit)

            val mapped: List<Community> = data.communities.mapNotNull { dto ->
                try {
                    val createdByEmail = dto.createdBy?.email
                    val creatorIsMe = !createdByEmail.isNullOrBlank() && createdByEmail.equals(email, true)
                    val myRoleRaw = dto.communityUsers
                        ?.firstOrNull { cu -> cu.user.email.equals(email, true) }
                        ?.role
                        ?.trim()
                        ?.uppercase()
                    val isOwner = creatorIsMe || myRoleRaw == "OWNER" || myRoleRaw == "CREATOR"
                    val isModerator = when {
                        myRoleRaw == null -> false
                        myRoleRaw.contains("ADMIN") -> true
                        myRoleRaw == "MODERATOR" || myRoleRaw == "MANAGER" || myRoleRaw == "OWNER" || myRoleRaw == "CREATOR" -> true
                        else -> false
                    }
                    val isMember = true // API returns only communities I belong to

                    Community(
                        communityId = dto.communityId,
                        name = dto.name,
                        description = dto.description?.takeIf { it.isNotBlank() },
                        profilePicUrl = dto.imageUrl?.takeIf { it.isNotBlank() },
                        profilePicLocalPath = null,
                        coverPhotoUrl = dto.bannerUrl?.takeIf { it.isNotBlank() },
                        coverPhotoLocalPath = null,
                        category = null,
                        memberCount = 0,
                        postCount = 0,
                        isPrivate = false,
                        creatorId = dto.createdBy?.email,
                        creatorName = dto.createdBy?.username,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        isOwner = isOwner,
                        isMember = isMember,
                        isModerator = isModerator
                    )
                } catch (_: Exception) { null }
            }

            val ids = mapped.map { it.communityId }

            CommunityDatabase.getInstance(context).withTransaction {
                if (ids.isNotEmpty()) {
                    communityDao.deleteCommunitiesNotIn(ids)
                } else {
                    communityDao.deleteAllNonMyCommunities()
                }
                if (mapped.isNotEmpty()) {
                    communityDao.insertCommunities(mapped)
                }
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    // Helper: backfill my role for a few communities to classify admin correctly with minimal API cost
    private suspend fun backfillRolesIfMissing(limit: Int = 5) {
        runCatching {
            val list = communityDao.getAllCommunities()
            val targets = list.filter { !it.isOwner && !it.isModerator }.take(limit)
            for (c in targets) {
                // fetchMembers updates relationship flags in DB based on my role
                runCatching { fetchMembers(c.communityId) }
            }
        }
    }

    // Helper: fetch my communities from server, then refresh rooms for each (bootstrap after auth)
    suspend fun bootstrapCommunitiesAndRooms(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val email = userData.getEmail() ?: return@withContext Result.failure(IllegalStateException("Email not set"))
            val commRes = fetchMyCommunitiesRemote(email)
            if (commRes.isFailure) return@withContext Result.failure(commRes.exceptionOrNull()!!)

            // Backfill my role for a few communities so Dashboard classifies admin vs joined without opening detail
            backfillRolesIfMissing(limit = 5)

            val communities = communityDao.getAllCommunities()
            communities.forEach { c ->
                runCatching { refreshRooms(c.communityId) }
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun requestToJoinCommunity(communityName: String): Result<Unit> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val resp = api.requestToJoinCommunity(RequestJoinRequest(userEmail = email, communityName = communityName))
            if (resp.isSuccessful) Result.success(Unit) else Result.failure(RuntimeException("HTTP ${resp.code()}"))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun getMyPendingRequests(): Result<List<PendingRequest>> {
        return try {
            val requester = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val resp = api.getMyPendingRequestsRaw(requester)
            if (!resp.isSuccessful) return Result.failure(RuntimeException("HTTP ${resp.code()}"))
            val root: JsonElement? = resp.body()

            // Expect: { status, message, data: [ { communityId, communityName, requests: [ { userId, username, email } ] } ] }
            val dataArr: JsonArray? = when {
                root == null || root.isJsonNull -> null
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject -> root.asJsonObject.getAsJsonArray("data") ?: run {
                    // In case server returns the array directly in another key
                    val obj = root.asJsonObject
                    obj.entrySet().firstOrNull { it.value.isJsonArray }?.value?.asJsonArray
                }
                else -> null
            }

            val result = mutableListOf<PendingRequest>()
            dataArr?.forEach { groupEl ->
                val groupObj = groupEl.asJsonObject
                val communityId = groupObj.get("communityId")?.asString
                val communityName = groupObj.get("communityName")?.asString
                if (communityId.isNullOrBlank() || communityName.isNullOrBlank()) return@forEach

                val requests = groupObj.getAsJsonArray("requests") ?: JsonArray()
                requests.forEach { item ->
                    val req = item.asJsonObject
                    val userId = req.get("userId")?.asString
                    val email = req.get("email")?.asString
                    val username = req.get("username")?.asString
                    if (email.isNullOrBlank()) return@forEach

                    // Synthesize a stable id for UI processing state
                    val synthesizedId = (communityId + ":" + (userId ?: email))

                    result += PendingRequest(
                        id = synthesizedId,
                        communityId = communityId,
                        communityName = communityName,
                        communityImageUrl = null,
                        userEmail = email,
                        userName = username,
                        userAvatarUrl = null,
                        requestedAt = "",
                        status = "PENDING"
                    )
                }
            }
            Result.success(result)
        } catch (t: Throwable) {
            Result.failure(if (t is JsonParseException) RuntimeException("Malformed response", t) else t)
        }
    }

    suspend fun getPendingRequestsCount(): Result<Int> {
        return try {
            val requester = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val resp = api.getMyPendingRequestsRaw(requester)
            if (!resp.isSuccessful) return Result.failure(RuntimeException("HTTP ${resp.code()}"))
            val root: JsonElement? = resp.body()

            val dataArr: JsonArray? = when {
                root == null || root.isJsonNull -> null
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject -> root.asJsonObject.getAsJsonArray("data") ?: run {
                    val obj = root.asJsonObject
                    obj.entrySet().firstOrNull { it.value.isJsonArray }?.value?.asJsonArray
                }
                else -> null
            }

            var count = 0
            dataArr?.forEach { groupEl ->
                val groupObj = groupEl.asJsonObject
                val requests = groupObj.getAsJsonArray("requests")
                if (requests != null) count += requests.size()
            }
            Result.success(count)
        } catch (t: Throwable) {
            Result.failure(if (t is JsonParseException) RuntimeException("Malformed response", t) else t)
        }
    }

    suspend fun acceptJoinRequest(request: PendingRequest): Result<Unit> {
        Log.d("CommunityRepo", "acceptJoinRequest ENTRY - request=$request")
        return try {
            val creatorEmail = userData.getEmail()
            if (creatorEmail.isNullOrBlank()) return Result.failure(IllegalStateException("Email not set"))

            val userEmail = request.userEmail
            val communityName = request.communityName
            if (userEmail.isBlank() || communityName.isBlank()) {
                Log.e("CommunityRepo", "ABORT: Missing fields userEmail=$userEmail, communityName=$communityName")
                return Result.failure(IllegalArgumentException("Request data incomplete: userEmail or communityName is missing"))
            }

            val payload = AcceptRequest(
                communityName = communityName,
                creatorEmail = creatorEmail,
                userEmail = userEmail
            )
            Log.d("CommunityRepo", "acceptJoinRequest - payload=$payload")
            val resp = api.acceptRequest(payload)
            Log.d("CommunityRepo", "acceptJoinRequest - HTTP ${resp.code()} isSuccessful=${resp.isSuccessful} bodyStatus=${resp.body()?.status} msg=${resp.body()?.message}")
            return if (resp.isSuccessful && (resp.body()?.status in listOf(200, 201))) {
                // After accepting a request, refresh My Communities to reflect any changes immediately
                runCatching { fetchMyCommunitiesRemote(creatorEmail) }
                Result.success(Unit)
            } else {
                Result.failure(RuntimeException(resp.body()?.message ?: "HTTP ${resp.code()}"))
            }
        } catch (t: Throwable) {
            Log.e("CommunityRepo", "acceptJoinRequest - exception: ${t.message}", t)
            Result.failure(t)
        }
    }

    suspend fun rejectJoinRequest(request: PendingRequest): Result<Unit> {
        Log.d("CommunityRepo", "rejectJoinRequest ENTRY - request=$request")
        return try {
            val creatorEmail = userData.getEmail()
            if (creatorEmail.isNullOrBlank()) return Result.failure(IllegalStateException("Email not set"))

            val userEmail = request.userEmail
            val communityName = request.communityName
            if (userEmail.isBlank() || communityName.isBlank()) {
                Log.e("CommunityRepo", "ABORT: Missing fields userEmail=$userEmail, communityName=$communityName")
                return Result.failure(IllegalArgumentException("Request data incomplete: userEmail or communityName is missing"))
            }

            val payload = RejectRequest(
                communityName = communityName,
                creatorEmail = creatorEmail,
                userEmail = userEmail
            )
            Log.d("CommunityRepo", "rejectJoinRequest - payload=$payload")
            val resp = api.rejectRequest(payload)
            Log.d("CommunityRepo", "rejectJoinRequest - HTTP ${resp.code()} isSuccessful=${resp.isSuccessful} bodyStatus=${resp.body()?.status} msg=${resp.body()?.message}")
            if (resp.isSuccessful && (resp.body()?.status in listOf(200, 201))) Result.success(Unit)
            else Result.failure(RuntimeException(resp.body()?.message ?: "HTTP ${resp.code()}"))
        } catch (t: Throwable) {
            Log.e("CommunityRepo", "rejectJoinRequest - exception: ${t.message}", t)
            Result.failure(t)
        }
    }

    suspend fun createInviteLink(communityId: String): Result<DataInviteLink> {
        return try {
            val inviter = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val resp = api.createInviteLink(communityId, CommunityInviteLinkRequest(inviterEmail = inviter))
            if (resp.isSuccessful && (resp.body()?.status in listOf(200, 201))) {
                val link = resp.body()!!.data
                Result.success(link)
            } else {
                Result.failure(RuntimeException("HTTP ${resp.code()} - ${resp.body()?.message ?: ""}"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
    suspend fun joinCommunityByLink(communityId: String, inviteCode: String): Result<Unit> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val req = JoinCommunityByLinkRequest(communityId = communityId, inviteCode = inviteCode, acceptorEmail = email)
            val resp = api.joinCommunityByLink(req)
            if (resp.isSuccessful && (resp.body()?.status in listOf(200, 201))) {
                try { fetchMyCommunitiesRemote(email) } catch (_: Exception) {}
                Result.success(Unit)
            } else {
                val code = resp.code()
                val msg = resp.body()?.message ?: "HTTP $code"
                Result.failure(RuntimeException(msg))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun leaveCommunityRemote(communityName: String): Result<Unit> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val resp = api.leaveCommunity(LeaveCommunityRequest(communityName = communityName, userEmail = email))
            if (resp.isSuccessful && resp.body()?.status in listOf(200, 201)) {
                // Immediately reconcile local cache from server (will prune left community)
                runCatching { fetchMyCommunitiesRemote(email) }
                Result.success(Unit)
            }
            else Result.failure(RuntimeException("Failed: ${resp.code()}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    // Observe rooms for a community
    fun observeRooms(communityId: String) = roomDao.observeRooms(communityId)


    // Fetch and persist rooms
    suspend fun refreshRooms(communityId: String): Result<Unit> {
        return try {
            val resp = api.getAllRooms(communityId)
            if (!resp.isSuccessful) return Result.failure(RuntimeException("HTTP ${resp.code()}"))
            val body = resp.body()
            val dataEl = body?.data

            // Reuse existing JSON mapping logic from getAllRooms()
            fun extractArray(el: com.google.gson.JsonElement?): com.google.gson.JsonArray? {
                if (el == null || el.isJsonNull) return null
                if (el.isJsonArray) return el.asJsonArray
                if (el.isJsonObject) {
                    val obj = el.asJsonObject
                    val keys = arrayOf("rooms", "chatRooms", "data", "content", "results", "items")
                    for (k in keys) {
                        if (obj.has(k)) {
                            val child = obj.get(k)
                            val asArr = extractArray(child)
                            if (asArr != null) return asArr
                        }
                    }
                }
                return null
            }
            fun mapRoom(el: com.google.gson.JsonElement): RoomEntity? {
                if (el.isJsonPrimitive) {
                    val v = el.asJsonPrimitive
                    val s = if (v.isString) v.asString else v.toString()
                    return RoomEntity(id = s, communityId = communityId, name = s, roomCode = "")
                }
                if (!el.isJsonObject) return null
                val o = el.asJsonObject
                val id = when {
                    o.has("id") && !o.get("id").isJsonNull -> o.get("id").asString
                    o.has("_id") && !o.get("_id").isJsonNull -> o.get("_id").asString
                    o.has("roomId") && !o.get("roomId").isJsonNull -> o.get("roomId").asString
                    else -> null
                } ?: return null
                val name = when {
                    o.has("name") && !o.get("name").isJsonNull -> o.get("name").asString
                    o.has("roomName") && !o.get("roomName").isJsonNull -> o.get("roomName").asString
                    o.has("title") && !o.get("title").isJsonNull -> o.get("title").asString
                    else -> id
                }
                val code = when {
                    o.has("roomCode") && !o.get("roomCode").isJsonNull -> o.get("roomCode").asString
                    o.has("room_code") && !o.get("room_code").isJsonNull -> o.get("room_code").asString
                    o.has("type") && !o.get("type").isJsonNull -> o.get("type").asString
                    o.has("code") && !o.get("code").isJsonNull -> o.get("code").asString
                    else -> ""
                }
                return RoomEntity(id = id, communityId = communityId, name = name, roomCode = code)
            }

            val arr = extractArray(dataEl)
            val rooms = arr?.mapNotNull { mapRoom(it) } ?: emptyList()
            // Persist: replace existing rooms for this community
            roomDao.deleteByCommunity(communityId)
            if (rooms.isNotEmpty()) roomDao.insertAll(rooms)
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

}
