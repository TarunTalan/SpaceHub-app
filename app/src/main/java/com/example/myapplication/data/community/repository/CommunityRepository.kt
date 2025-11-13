package com.example.myapplication.data.community.repository

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.example.myapplication.data.chat_room.model.DataChatRoom
import com.example.myapplication.data.community.dao.CommunityDao
import com.example.myapplication.data.community.database.CommunityDatabase
import com.example.myapplication.data.community.model.*
import com.example.myapplication.data.network.NetworkModule
import com.example.myapplication.data.user.UserDataManager
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody


@SuppressLint("StaticFieldLeak")
class CommunityRepository private constructor(private val context: Context) {

    private val communityDao: CommunityDao = CommunityDatabase.getInstance(context).communityDao()
    private val api = NetworkModule.createApiService(context)
    private val userData = UserDataManager.getInstance(context)
    private val roomDao = CommunityDatabase.getInstance(context).roomDao()

    // In-flight dedupe and small TTL cache for fetchMembers to prevent duplicate network calls
    private val _memberRequestsLock = Any()
    private val _memberInFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<Result<List<Member>>>>()
    private val _memberCache = mutableMapOf<String, Pair<Result<List<Member>>, Long>>() // Pair(result, timestampMs)
    private val memberRequestScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    companion object {
        @Volatile
        private var INSTANCE: CommunityRepository? = null

        fun getInstance(context: Context): CommunityRepository {
            return INSTANCE ?: synchronized(this) {
                // Store applicationContext to avoid leaking Activity or other short-lived contexts
                val appCtx = context.applicationContext
                INSTANCE ?: CommunityRepository(appCtx).also { INSTANCE = it }
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

    suspend fun fetchMembers(communityId: String, force: Boolean = false): Result<List<Member>> {
        // First, check in the short TTL cache unless caller forced a fresh network call
        if (!force) {
            synchronized(_memberRequestsLock) {
                val cached = _memberCache[communityId]
                val now = System.currentTimeMillis()
                val isExpired = cached?.let { (it.second + 5 * 60 * 1000) < now } ?: true // 5 minutes TTL
                if (!isExpired) {
                    // Return cached result immediately if not expired
                    return cached.first
                }
            }
        }

        // If expired, forced or not present in cache, proceed with network request
        val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))

        try {
            // If there is already an in-flight request, await and return its result
            val existingDeferred = synchronized(_memberRequestsLock) { _memberInFlight[communityId] }
            if (existingDeferred != null) {
                return existingDeferred.await()
            }

            // Create new in-flight request
            val newDeferred = memberRequestScope.async<Result<List<Member>>> {
                try {
                    val resp = api.getAllMembers(GetAllMembersRequest(communityId = communityId, requesterEmail = email))
                    if (resp.isSuccessful) {
                        val body = resp.body()
                        val list = body?.data?.members ?: emptyList()
                        // Update relationship flags locally based on my role (handle synonyms)
                        val myRole = list.firstOrNull { it.email.equals(email, true) }?.role?.trim()?.uppercase()
                        // Fallback: some backends omit the creator from the members list. If myRole is null,
                        // consult the locally persisted community.creatorId to decide ownership.
                        val creatorIdFromDb = runCatching { communityDao.getCommunityById(communityId)?.creatorId }.getOrNull()
                        val creatorIsMe = !creatorIdFromDb.isNullOrBlank() && creatorIdFromDb.equals(email, true)
                        val isOwner = myRole == "OWNER" || myRole == "CREATOR"
                            || (myRole == null && creatorIsMe)
                        val isModerator = when {
                            myRole == null -> false
                            myRole.contains("ADMIN") -> true
                            myRole == "MODERATOR" || myRole == "MANAGER" || myRole == "OWNER" || myRole == "CREATOR" -> true
                            else -> false
                        }
                        val isMember = list.any { it.email.equals(email, true) }
                        runCatching { communityDao.updateRelationship(communityId, isOwner, isMember, isModerator) }
                        // Persist member count so dashboard and other UI can reflect accurate totals
                        runCatching { communityDao.updateMemberCount(communityId, list.size) }
                        // Cache the result with the current timestamp (even when caller requested fresh data)
                        synchronized(_memberRequestsLock) {
                            _memberCache[communityId] = Result.success(list) to System.currentTimeMillis()
                        }
                        Result.success(list)
                    } else {
                        Result.failure(RuntimeException("HTTP ${resp.code()}"))
                    }
                } catch (t: Throwable) {
                    Result.failure(t)
                } finally {
                    // Remove from in-flight map after completion
                    synchronized(_memberRequestsLock) {
                        _memberInFlight.remove(communityId)
                    }
                }
            }

            synchronized(_memberRequestsLock) { _memberInFlight[communityId] = newDeferred }

            val result = newDeferred.await()
            return result
        } catch (t: Throwable) {
            return Result.failure(t)
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

    // Enhanced removeMember that also refreshes member list and updates member count. Use this for UI actions.
    suspend fun removeMemberAndRefresh(communityId: String, targetUserEmail: String?): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val email = userData.getEmail() ?: return@withContext Result.failure(IllegalStateException("Email not set"))
                val resp = api.removeMember(RemoveMemberRequest(communityId, email, targetUserEmail))
                if (resp.isSuccessful && resp.body()?.status in listOf(200, 201)) {
                    // Refresh members list and update member count in DB
                    val membersRes = runCatching { fetchMembers(communityId) }.getOrNull()
                    val members = membersRes?.getOrNull() ?: emptyList()
                    runCatching { communityDao.updateMemberCount(communityId, members.size) }
                    Result.success(Unit)
                } else {
                    Result.failure(RuntimeException("Failed: ${resp.code()}"))
                }
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
    }

    suspend fun leaveCommunity(communityId: String, communityName: String): Result<Unit> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val resp = api.leaveCommunity(LeaveCommunityRequest(communityName = communityName, userEmail = email))
            if (resp.isSuccessful && resp.body()?.status in listOf(200, 201)) {
                // Minimize API calls: update local DB immediately instead of refetching
                try { communityDao.deleteCommunityById(communityId) } catch (_: Exception) {}
                Result.success(Unit)
            } else {
                // Try to extract server-provided message deterministically.
                val rawErr = try { resp.errorBody()?.string() } catch (_: Exception) { null }
                val err = when {
                    // If the typed body contains a message field (some backends include it even on errors), prefer that
                    !resp.body()?.message.isNullOrBlank() -> resp.body()!!.message
                    // Try parsing raw error JSON for 'message'/'error' keys
                    !rawErr.isNullOrBlank() -> try {
                        val parsed = com.example.myapplication.data.network.ResponseParser.parseError(okhttp3.ResponseBody.create(null, rawErr))
                        if (!parsed.isNullOrBlank()) parsed else "HTTP ${resp.code()}"
                    } catch (_: Exception) {
                        rawErr
                    }
                    else -> "HTTP ${resp.code()}"
                }
                Result.failure(RuntimeException(err))
            }
        } catch (t: Throwable) { Result.failure(t) }
    }

    // Rooms
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
        return withContext(Dispatchers.IO) {
            try {
                // Resolve parent room's authoritative roomCode.
                // 1) Local DB entry for this community (roomDao) -> use its roomCode if present
                // 2) Remote call getAllRooms(communityId) to fetch canonical list and use the matching entry's roomCode
                // 3) Fallback to provided roomId (last-resort)
                var parentRoomCode: String? = null
                try {
                    val localRooms = roomDao.getRooms(communityId)
                    val found = localRooms.firstOrNull { r -> r.id == roomId || r.roomCode == roomId || r.name == roomId }
                    if (found != null) {
                        parentRoomCode = found.roomCode.takeIf { it.isNotBlank() } ?: found.id
                    }
                } catch (_: Exception) { }

                if (parentRoomCode.isNullOrBlank()) {
                    try {
                        val allResp = api.getAllRooms(communityId)
                        if (allResp.isSuccessful) {
                            val list = allResp.body()?.data ?: emptyList()
                            val match = list.firstOrNull { r -> r.id == roomId || r.roomCode == roomId || r.name == roomId }
                            if (match != null) parentRoomCode = match.roomCode.takeIf { it.isNotBlank() } ?: match.id
                        }
                    } catch (_: Exception) {
                        // ignore remote lookup failure
                    }
                }

                // Fallback to provided roomId if nothing resolved
                val parentCode: String = parentRoomCode?.takeIf { it.isNotBlank() } ?: roomId

                // Build multipart parts per API contract: name, chatRoomCode
                val namePart = chatRoomName.trim().toRequestBody("text/plain".toMediaTypeOrNull())
                val codePart = parentCode.trim().toRequestBody("text/plain".toMediaTypeOrNull())

                // Debug log (safe): indicate which code we're sending
                // Timber/Log used elsewhere; use android.util.Log to avoid adding deps
                try { android.util.Log.d("CommunityRepo", "createChatRoom: sending roomCode=$parentCode for community=$communityId") } catch (_: Exception) {}
                val resp = api.createChatRoom(namePart, codePart)
                if (resp.isSuccessful && (resp.body()?.status in listOf(200, 201))) {
                    val created = resp.body()!!.data
                    // DataChatRoom model uses 'chatRoomCode' as the field name
                    val dataChatRoom = DataChatRoom(
                        createdAt = created.createdAt,
                        id = created.id,
                        name = created.name,
                        chatRoomCode = created.chatRoomCode
                    )
                    Result.success(dataChatRoom)
                } else {
                    val errBody = try { resp.errorBody()?.string() } catch (_: Exception) { null }
                    val msg = resp.body()?.message ?: errBody ?: "HTTP ${resp.code()}"
                    Result.failure(RuntimeException(msg))
                }
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
    }

    // Fetch summary of chat rooms inside a specific parent room (by roomCode)
    suspend fun getChatRoomSummary(roomCode: String): Result<List<DataChatRoom>> {
        return withContext(Dispatchers.IO) {
            try {
                val resp = api.getChatRoomSummary(roomCode)
                if (!resp.isSuccessful) return@withContext Result.failure(RuntimeException("HTTP ${resp.code()}"))
                val body = resp.body() ?: return@withContext Result.failure(RuntimeException("Empty response"))
                if (body.status !in listOf(200, 201)) return@withContext Result.failure(RuntimeException(body.message))

                // `Data` model fields are non-nullable (chatRoomCode, name)
                val mapped = body.data.map { item ->
                    DataChatRoom(
                        createdAt = 0L,
                        id = item.chatRoomCode,
                        name = item.name,
                        chatRoomCode = item.chatRoomCode
                    )
                }
                Result.success(mapped)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
    }

    suspend fun deleteRoom(communityId: String, roomId: String): Result<Unit> {
        return try {
            val email = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))

            // First attempt: try deleting with provided roomId
            var resp = api.deleteRoom(communityId, roomId, email)
            if (resp.isSuccessful && (resp.body()?.status in listOf(200, 201))) {
                runCatching { refreshRooms(communityId) }
                return Result.success(Unit)
            }

            // If server returned not found / bad request, try to resolve actual server-side id by fetching rooms
            val bodyMsg = try { resp.body()?.message } catch (_: Exception) { null }
            val statusCode = resp.code()
            if (statusCode == 400 || statusCode == 404 || (bodyMsg?.contains("not found", true) == true)) {
                try {
                    val allResp = api.getAllRooms(communityId)
                    if (allResp.isSuccessful) {
                        val list = allResp.body()?.data ?: emptyList()
                        // find room by roomCode or by matching effective id
                        val match = list.firstOrNull { r -> r.roomCode == roomId || r.id == roomId }
                        val actualId = match?.id
                        if (!actualId.isNullOrBlank() && actualId != roomId) {
                            resp = api.deleteRoom(communityId, actualId, email)
                            if (resp.isSuccessful && (resp.body()?.status in listOf(200, 201))) {
                                runCatching { refreshRooms(communityId) }
                                return Result.success(Unit)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // ignore and fallthrough to return original failure
                }
            }

            // If we reach here, deletion failed
            val code = resp.code()
            val msg = try { resp.body()?.message } catch (_: Exception) { null }
            Result.failure(RuntimeException(msg ?: "HTTP $code"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    suspend fun getAllRooms(communityId: String): Result<List<DataRoom>> {
        return try {
            val resp = api.getAllRooms(communityId)
            if (!resp.isSuccessful) return Result.failure(RuntimeException("HTTP ${'$'}{resp.code()}"))
            val body = resp.body()
            // If the typed API model provides a list of DataRoom, use it directly
            val dataList: List<DataRoom>? = body?.data
            if (dataList != null) {
                // Use server-side id as DataRoom.id so UI that relies on `roomId` receives authoritative id.
                // Keep roomCode as provided so chat flows can use chatRoomCode when needed.
                val normalized = dataList.map { r ->
                    DataRoom(id = r.id, name = r.name, roomCode = r.roomCode, newChatRooms = r.newChatRooms, voiceRooms = r.voiceRooms)
                }
                Log.d("CommunityRepo", "getAllRooms: returning ${normalized.size} rooms")
                return Result.success(normalized)
            }

            // Fallback: empty list
            Result.success(emptyList())
        } catch (t: Throwable) { Result.failure(t) }
     }

    suspend fun fetchMemberCount(communityId: String): Result<Int> {
        return try {
            val membersRes = fetchMembers(communityId)
            if (membersRes.isSuccess) {
                val list = membersRes.getOrNull().orEmpty()
                val count = list.size
                Result.success(count)
            } else {
                // If fetching full members failed, fall back to calling lightweight count endpoint (if any)
                // For now, return failure using the original error
                Result.failure(membersRes.exceptionOrNull() ?: RuntimeException("Failed to fetch members"))
            }
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

            // Check admin/owner permission. Consider various signals: isOwner flag, isModerator, explicit role string,
            // or creatorId equality. Role checks are case-insensitive and allow values like "ADMIN", "OWNER", "CREATOR".
            val roleRaw = community.role?.trim()?.uppercase()
            val roleIndicatesAdmin = roleRaw != null && listOf("ADMIN", "OWNER", "CREATOR", "MANAGER", "MODERATOR").any { roleRaw.contains(it) }
            val isAdmin = community.isOwner || community.isModerator || roleIndicatesAdmin || (!community.creatorId.isNullOrBlank() && community.creatorId.equals(email, true))
            if (!isAdmin) {
                Log.w("CommunityRepo", "deleteCommunityRemote denied: user=$email not admin of community=${community.communityId}; role=$roleRaw, isOwner=${community.isOwner}, isModerator=${community.isModerator}, creatorId=${community.creatorId}")
                return Result.failure(IllegalAccessException("Only admins can delete community"))
            }

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

    // Upload community banner image and update community coverPhotoUrl locally
    suspend fun uploadCommunityBannerRemote(communityId: String, requesterEmail: String, imagePart: okhttp3.MultipartBody.Part?): Result<String?> {
        return try {
            val resp = api.uploadCommunityBanner(communityId = communityId, requesterEmail = requesterEmail, file = imagePart)
            if (resp.isSuccessful && (resp.body()?.status in listOf(200, 201))) {
                // API currently doesn't provide a stable field for returned image URL in our model.
                // Return null to indicate success but no URL parsed. Caller may refresh community details separately if needed.
                Result.success(null)
            } else {
                val code = resp.code()
                val bodyMsg = try { resp.body()?.message } catch (_: Exception) { null }
                Result.failure(RuntimeException("HTTP $code - ${bodyMsg ?: ""}"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun fetchMyCommunitiesRemote(requesterEmail: String? = null): Result<Unit> {
        return try {
            val email = requesterEmail ?: userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val resp = api.getMyCommunities(email)
            if (!resp.isSuccessful) return Result.failure(RuntimeException("HTTP ${resp.code()}"))

            val body = resp.body()
            val data = body?.data ?: return Result.success(Unit)

            // Snapshot existing local communities to avoid overwriting creator/isOwner when API omits those fields
            val existingMap: Map<String, Community> = runCatching { communityDao.getAllCommunities().associateBy { it.communityId } }.getOrDefault(emptyMap())

            val mapped: List<Community> = data.communities.mapNotNull { dto ->
                try {
                    val myRoleRaw = dto.communityUsers
                        ?.firstOrNull { cu -> cu.user.email.equals(email, true) }
                        ?.role
                        ?.trim()
                        ?.uppercase()

                    // Prefer API creator if provided, otherwise preserve existing local creatorId
                    val creatorEmailFromApi = dto.createdBy?.email
                    val existing = existingMap[dto.communityId]
                    val creatorEmail = if (!creatorEmailFromApi.isNullOrBlank()) creatorEmailFromApi else existing?.creatorId
                    val creatorName = dto.createdBy?.username ?: existing?.creatorName
                    val creatorIsMe = !creatorEmail.isNullOrBlank() && creatorEmail.equals(email, true)

                    // Prefer role from server; fallback to createdBy==me or existing isOwner only if role is missing
                    val isOwner = when {
                        myRoleRaw == "OWNER" || myRoleRaw == "CREATOR" -> true
                        myRoleRaw == null && (creatorIsMe || (existing?.isOwner == true)) -> true
                        else -> false
                    }
                    val isModerator = when {
                        myRoleRaw == null -> existing?.isModerator ?: false
                        myRoleRaw.contains("ADMIN") -> true
                        myRoleRaw == "MODERATOR" || myRoleRaw == "MANAGER" || myRoleRaw == "OWNER" || myRoleRaw == "CREATOR" -> true
                        else -> false
                    }
                    val isMember = true // Server returns only communities I belong to

                    Community(
                        communityId = dto.communityId,
                        name = dto.name,
                        description = dto.description?.takeIf { it.isNotBlank() } ?: existing?.description,
                        profilePicUrl = dto.imageUrl?.takeIf { it.isNotBlank() } ?: existing?.profilePicUrl,
                        profilePicLocalPath = existing?.profilePicLocalPath,
                        coverPhotoUrl = dto.bannerUrl?.takeIf { it.isNotBlank() } ?: existing?.coverPhotoUrl,
                        coverPhotoLocalPath = existing?.coverPhotoLocalPath,
                        category = existing?.category,
                        memberCount = existing?.memberCount ?: 0,
                        postCount = existing?.postCount ?: 0,
                        isPrivate = existing?.isPrivate ?: false,
                        creatorId = creatorEmail,
                        creatorName = creatorName,
                        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        // Important: attach the role string from server so UI can classify admin/owner correctly
                        role = dto.role?.takeIf { it.isNotBlank() } ?: existing?.role,
                        isOwner = isOwner,
                        isMember = isMember,
                        isModerator = isModerator
                    )
                } catch (_: Exception) { null }
            }

            val ids = mapped.map { it.communityId }

            // Preserve only those existing communities that are owners AND whose creatorId matches the requester email.
            // This avoids preserving communities created by a different account when the user has switched accounts.
            val existingOwners = existingMap.filter { (_, v) ->
                v.isOwner && !v.creatorId.isNullOrBlank() && v.creatorId.equals(email, true)
            }.keys
            val preservedIds = (ids + existingOwners).distinct()

            // fetchMyCommunitiesRemote: server returned ${mapped.size} communities

             CommunityDatabase.getInstance(context).withTransaction {
                if (preservedIds.isNotEmpty()) {
                    communityDao.deleteCommunitiesNotIn(preservedIds)
                } else {
                    // Server returned empty list and no preserved owners for this email -> remove all local communities.
                    // This avoids retaining communities created by previously-signed-in accounts.
                    communityDao.deleteAllCommunities()
                }
                if (mapped.isNotEmpty()) {
                    communityDao.insertCommunities(mapped)
                }
                // debug log: report resulting DB contents after transaction
                try {
                    // read back to warm DB caches (no debug logging)
                    communityDao.getAllCommunities()
                } catch (_: Exception) {
                    // ignore read failures during debug cleanup
                }
             }
             // Trigger a small backfill to fetch relationship flags for a few communities so
             // the 'isOwner'/'isMember' flags are populated quickly (helps getMyCommunitiesFlow).
             runCatching { backfillRolesIfMissing(limit = 8) }
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

    @Suppress("unused")
    suspend fun getPendingRequestsCount(): Result<Int> {
        return try {
            val requester = userData.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))
            val resp = api.getMyPendingRequestsRaw(requester)
            if (!resp.isSuccessful) return Result.failure(RuntimeException("HTTP ${resp.code()}"))
            val root: JsonElement? = resp.body()

            val dataArr: JsonArray? = when {
                root == null || root.isJsonNull -> null
                root is JsonObject -> root.asJsonObject.getAsJsonArray("data") ?: run {
                    val obj = root.asJsonObject
                    obj.entrySet().firstOrNull { it.value is JsonArray }?.value?.asJsonArray
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
                // Ensure local DB preserves owner relationship for this community (defensive)
                runCatching {
                    val cid = request.communityId
                    var match: Community? = null
                    if (cid.isNotBlank()) {
                        match = communityDao.getCommunityById(cid)
                    }
                    if (match == null) {
                        // Fallback to name-based match
                        val localList = communityDao.getAllCommunities()
                        match = localList.firstOrNull { it.name.equals(communityName, true) }
                    }
                    if (match != null) {
                        val updated = match.copy(creatorId = creatorEmail, creatorName = match.creatorName ?: "", isOwner = true, isMember = true)
                        communityDao.insertCommunity(updated)
                        Log.d("CommunityRepo", "acceptJoinRequest: updated local community ${updated.communityId} as owner")
                    } else if (cid.isNotBlank()) {
                        // Insert minimal community entry if not present locally to avoid losing owner status on refresh
                        val newCommunity = Community(
                            communityId = cid,
                            name = communityName,
                            description = null,
                            profilePicUrl = null,
                            profilePicLocalPath = null,
                            coverPhotoUrl = null,
                            coverPhotoLocalPath = null,
                            category = null,
                            memberCount = 0,
                            postCount = 0,
                            isPrivate = false,
                            creatorId = creatorEmail,
                            creatorName = null,
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                            role = null,
                            isOwner = true,
                            isMember = true,
                            isModerator = false
                        )
                        communityDao.insertCommunity(newCommunity)
                        Log.d("CommunityRepo", "acceptJoinRequest: inserted minimal local community $cid as owner")
                    } else {
                        Log.w("CommunityRepo", "acceptJoinRequest: could not find local community to mark owner (no id and no name match)")
                    }
                }
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

    @Suppress("unused")
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
            if (!resp.isSuccessful) return Result.failure(RuntimeException("HTTP ${'$'}{resp.code()}"))
            val body = resp.body()

            // If backend returns typed list, use it directly
            val dataList: List<DataRoom>? = body?.data
            val rooms: List<RoomEntity> = if (!dataList.isNullOrEmpty()) {
                // Persist server-side id as primary id; store roomCode separately.
                dataList.mapNotNull { r ->
                    try {
                        RoomEntity(id = r.id, communityId = communityId, name = r.name, roomCode = r.roomCode)
                    } catch (_: Exception) { null }
                }
            } else {
                // Fallback to previous flexible JSON parsing if needed (older endpoints)
                val dataEl: JsonElement? = body?.data as? JsonElement
                fun extractArray(el: JsonElement?): JsonArray? {
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
                fun mapRoom(el: JsonElement): RoomEntity? {
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
                    // Persist server id as primary id and roomCode separately
                    return RoomEntity(id = id, communityId = communityId, name = name, roomCode = code)
                 }

                val arr = extractArray(dataEl)
                val parsedRooms: List<RoomEntity> = arr?.mapNotNull { mapRoom(it) } ?: emptyList()
                parsedRooms
             }

             // Persist: replace existing rooms for this community
             roomDao.deleteByCommunity(communityId)
             if (rooms.isNotEmpty()) roomDao.insertAll(rooms)
             Result.success(Unit)
         } catch (t: Throwable) {
             Result.failure(t)
         }
     }

    // Return cached rooms for a community from local DB (fast, no network)
    suspend fun getLocalRooms(communityId: String): List<DataRoom> = withContext(Dispatchers.IO) {
        try {
            val entities = roomDao.getRooms(communityId)
            // Return server-side id (RoomEntity.id) as DataRoom.id so callers receive authoritative roomId.
            return@withContext entities.map { e ->
                DataRoom(id = e.id, name = e.name, roomCode = e.roomCode)
            }
        } catch (t: Throwable) {
            Log.w("CommunityRepo", "getLocalRooms: failed to read DB for communityId=$communityId: ${t.message}")
            emptyList()
        }
    }


    fun invalidateMembersCache(communityId: String) {
        synchronized(_memberRequestsLock) {
            _memberCache.remove(communityId)
        }
    }
}
