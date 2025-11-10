package com.example.myapplication.data.groups.repository

import android.content.Context
import com.example.myapplication.data.community.model.RequestJoinRequest
import com.example.myapplication.data.community.model.RequestJoinResponse
import com.example.myapplication.data.groups.model.*
import com.example.myapplication.data.network.NetworkModule
import com.example.myapplication.data.user.UserDataManager
import com.example.myapplication.data.groups.database.GroupsDatabase
import com.example.myapplication.data.groups.model.LocalGroup
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
    // Use application context to avoid leaks; initialize api lazily with appContext
    private val appContext = context.applicationContext
    private val api by lazy { NetworkModule.createApiService(appContext) }
    private val userData = UserDataManager.getInstance(appContext)
    private val db by lazy { GroupsDatabase.getInstance(appContext) }
    private val groupDao by lazy { db.groupDao() }

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
            android.util.Log.d("LocalGroupRepo", "getAllLocalGroups: email = $email")
            val resp = api.getAllLocalGroups(email)
            if (!resp.isSuccessful) return@withContext Result.failure(RuntimeException("HTTP ${resp.code()}"))
            val body = resp.body()
            val list = body?.data ?: emptyList()
            android.util.Log.d("LocalGroupRepo", "getAllLocalGroups: returned list size = ${list.size}")
            // Persist into DB
            try {
                val entities = list.map { d ->
                    LocalGroup(
                        groupId = d.id,
                        name = d.name,
                        description = d.description,
                        imageUrl = d.imageUrl as? String,
                        memberEmails = d.memberEmails ?: emptyList(),
                        memberCount = d.totalMembers,
                        createdByEmail = d.createdByEmail,
                        chatRoomCode = d.chatRoomCode,
                        createdAt = d.createdAt,
                        updatedAt = d.updatedAt,
                        isOwner = false,
                        isMember = d.memberEmails.contains(userData.getEmail())
                    )
                }
                groupDao.insertGroups(entities)
                // delete groups not present anymore
                val ids = entities.map { it.groupId }
                groupDao.deleteGroupsNotIn(ids)
            } catch (e: Exception) { android.util.Log.e("LocalGroupRepo", "Failed to persist groups", e) }

            Result.success(list)
        } catch (t: Throwable) {
            android.util.Log.e("LocalGroupRepo", "getAllLocalGroups: error", t)
            Result.failure(t)
        }
    }

    suspend fun getLocalGroupDetails(groupId: String): Result<DataXX> = withContext(Dispatchers.IO) {
        try {
            val resp = api.getLocalGroupDetails(groupId)
            if (!resp.isSuccessful) return@withContext Result.failure(RuntimeException("HTTP ${resp.code()}"))
            val body = resp.body()
            val data = body?.data ?: return@withContext Result.failure(RuntimeException("Empty body"))
            // Persist single group
            try {
                val entity = LocalGroup(
                    groupId = data.id,
                    name = data.name,
                    description = data.description,
                    imageUrl = data.imageUrl as? String,
                    memberEmails = data.memberEmails ?: emptyList(),
                    memberCount = data.totalMembers,
                    createdByEmail = data.createdByEmail,
                    chatRoomCode = data.chatRoomCode,
                    createdAt = data.createdAt,
                    updatedAt = data.updatedAt,
                    isOwner = (data.createdByEmail == userData.getEmail()),
                    isMember = data.memberEmails.contains(userData.getEmail())
                )
                groupDao.insertGroup(entity)
            } catch (e: Exception) { android.util.Log.e("LocalGroupRepo", "Failed to persist group details", e) }

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
            if (!resp.isSuccessful) {
                val errBody = try { resp.errorBody()?.string() } catch (_: Exception) { null }
                return@withContext Result.failure(RuntimeException("HTTP ${resp.code()} - ${errBody ?: "no body"}"))
            }
            val body = resp.body() ?: return@withContext Result.failure(RuntimeException("Empty response body"))
            // If created, persist the new group returned inside body.data (CreateLocalGroupResponse.LocalGroupData)
            try {
                val d = body.data
                val entity = LocalGroup(
                    groupId = d.id,
                    name = d.name,
                    description = d.description,
                    imageUrl = d.imageUrl as? String,
                    memberEmails = d.memberEmails ?: emptyList(),
                    memberCount = d.totalMembers,
                    createdByEmail = d.createdByEmail,
                    chatRoomCode = d.chatRoomCode,
                    createdAt = d.createdAt,
                    updatedAt = d.updatedAt,
                    isOwner = (d.createdByEmail == userData.getEmail()),
                    isMember = d.memberEmails.contains(userData.getEmail())
                )
                groupDao.insertGroup(entity)
            } catch (e: Exception) { android.util.Log.e("LocalGroupRepo", "Failed to persist created group", e) }

            Result.success(body)
        } catch (t: Throwable) { Result.failure(t) }
    }

    suspend fun deleteLocalGroup(groupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val email = userData.getEmail() ?: return@withContext Result.failure(IllegalStateException("Email not set"))
            val req = DeleteLocalGroupRequest(groupId = groupId, requesterEmail = email)
            val resp = api.deleteLocalGroup(req)
            if (resp.isSuccessful && resp.body()?.status in listOf(200, 201)) {
                try {
                    // remove from DB
                    // Delete by id using DAO helper
                    groupDao.deleteGroupById(groupId)
                } catch (e: Exception) { android.util.Log.e("LocalGroupRepo", "Failed to remove group from DB", e) }
                Result.success(Unit)
            }
            else Result.failure(RuntimeException("HTTP ${resp.code()} - ${resp.errorBody()?.string()}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    // Delete all persisted local groups (used on logout to avoid cross-account residues)
    suspend fun deleteAllGroups() = withContext(Dispatchers.IO) {
        try {
            groupDao.deleteAllGroups()
        } catch (t: Throwable) {
            android.util.Log.w("LocalGroupRepo", "deleteAllGroups failed: ${t.message}")
        }
    }

    suspend fun getLocalGroupMembers(localGroupId: String): Result<List<DataXXX>> = withContext(Dispatchers.IO) {
        try {
            val resp = api.getLocalGroupMembers(localGroupId)
            if (!resp.isSuccessful) return@withContext Result.failure(RuntimeException("HTTP ${resp.code()}"))
            val body = resp.body()
            // API returns data as an array of member objects for local groups
            val list: List<DataXXX> = body?.data ?: emptyList()
            android.util.Log.d("LocalGroupRepo", "getLocalGroupMembers: parsed list size=${list.size} for id=$localGroupId")
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
            // update DB record if fields changed
            try {
                val d = body.data
                val entity = LocalGroup(
                    groupId = d.id,
                    name = d.name,
                    description = d.description,
                    imageUrl = d.imageUrl as? String,
                    memberEmails = d.memberEmails ?: emptyList(),
                    memberCount = d.totalMembers,
                    createdByEmail = d.createdByEmail,
                    chatRoomCode = d.chatRoomCode,
                    createdAt = d.createdAt,
                    updatedAt = d.updatedAt,
                    isOwner = (d.createdByEmail == userData.getEmail()),
                    isMember = d.memberEmails.contains(userData.getEmail())
                )
                groupDao.insertGroup(entity)
            } catch (e: Exception) { android.util.Log.e("LocalGroupRepo", "Failed to persist updated settings", e) }

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
            // update DB membership flag if join succeeded
            try {
                if (body.status == 200) {
                    // mark membership true in DB
                    groupDao.updateMembership(groupId, true)
                }
            } catch (e: Exception) { android.util.Log.e("LocalGroupRepo", "Failed to update membership in DB", e) }

            Result.success(body)
        } catch (t: Throwable) { Result.failure(t) }
    }

    // Create an invite link for a local group. Returns DataXXXXX which includes inviteLink and inviteCode
    suspend fun createLocalGroupInviteLink(groupId: String): Result<DataXXXXX> = withContext(Dispatchers.IO) {
        try {
            val email = userData.getEmail() ?: return@withContext Result.failure(IllegalStateException("Email not set"))
            val req = CreateLocalGroupInviteLinkRequest(inviterEmail = email)
            val resp = api.createLocalGroupInviteLink(groupId, req)
            if (!resp.isSuccessful) return@withContext Result.failure(RuntimeException("HTTP ${resp.code()} - ${resp.errorBody()?.string()}"))
            val body = resp.body() ?: return@withContext Result.failure(RuntimeException("Empty response"))
            val data = body.data
            Result.success(data)
        } catch (t: Throwable) { Result.failure(t) }
    }

    // Join a local group using an invite link/code. Persists group info and marks membership.
    suspend fun joinLocalGroupByLink(groupId: String, inviteCode: String): Result<DataXXXXXX> = withContext(Dispatchers.IO) {
        try {
            val email = userData.getEmail() ?: return@withContext Result.failure(IllegalStateException("Email not set"))
            val req = JoinGroupByLinkRequest(acceptorEmail = email, groupId = groupId, inviteCode = inviteCode)
            val resp = api.joinLocalGroupByLink(req)
            if (!resp.isSuccessful) return@withContext Result.failure(RuntimeException("HTTP ${resp.code()} - ${resp.errorBody()?.string()}"))
            val body = resp.body() ?: return@withContext Result.failure(RuntimeException("Empty response"))
            val d = body.data
            // Persist/update the group in DB
            try {
                val entity = LocalGroup(
                    groupId = d.id,
                    name = d.name,
                    description = d.description,
                    imageUrl = d.imageUrl,
                    memberEmails = d.members.mapNotNull { it.email },
                    memberCount = d.members.size,
                    createdByEmail = d.createdBy.email,
                    chatRoomCode = d.chatRoom.roomCode,
                    createdAt = d.createdAt,
                    updatedAt = d.updatedAt,
                    isOwner = (d.createdBy.email == userData.getEmail()),
                    isMember = true
                )
                groupDao.insertGroup(entity)
            } catch (e: Exception) { android.util.Log.e("LocalGroupRepo", "Failed to persist joined group", e) }

            Result.success(d)
        } catch (t: Throwable) { Result.failure(t) }
    }

    // ----- Group-level chat room APIs -----
    // Fetch chat rooms inside a given local group (use group.chatRoomCode or accept a roomCode)
    suspend fun getGroupChatRooms(groupRoomCode: String): Result<List<com.example.myapplication.data.chat_room.model.DataChatRoom>> = withContext(Dispatchers.IO) {
        try {
            val resp = api.getChatRoomSummary(groupRoomCode)
            if (!resp.isSuccessful) return@withContext Result.failure(RuntimeException("HTTP ${resp.code()}"))
            val body = resp.body() ?: return@withContext Result.failure(RuntimeException("Empty response"))
            if (body.status !in listOf(200, 201)) return@withContext Result.failure(RuntimeException(body.message))
            val mapped = body.data.map { d -> com.example.myapplication.data.chat_room.model.DataChatRoom(createdAt = 0L, id = d.chatRoomCode, name = d.name, chatRoomCode = d.chatRoomCode) }
            Result.success(mapped)
        } catch (t: Throwable) { Result.failure(t) }
    }

    // Create a chat room inside a local group using the group's roomCode (or the provided roomCode)
    suspend fun createChatRoomInGroup(groupRoomCode: String, chatRoomName: String): Result<com.example.myapplication.data.chat_room.model.DataChatRoom> = withContext(Dispatchers.IO) {
        try {
            val namePart = chatRoomName.trim().toRequestBody("text/plain".toMediaTypeOrNull())
            val codePart = groupRoomCode.trim().toRequestBody("text/plain".toMediaTypeOrNull())
            val resp = api.createChatRoom(namePart, codePart)
            if (resp.isSuccessful && (resp.body()?.status in listOf(200, 201))) {
                val created = resp.body()!!.data
                val dataChatRoom = com.example.myapplication.data.chat_room.model.DataChatRoom(createdAt = created.createdAt, id = created.id, name = created.name, chatRoomCode = created.chatRoomCode)
                Result.success(dataChatRoom)
            } else {
                val errBody = try { resp.errorBody()?.string() } catch (_: Exception) { null }
                val msg = resp.body()?.message ?: errBody ?: "HTTP ${resp.code()}"
                Result.failure(RuntimeException(msg))
            }
        } catch (t: Throwable) { Result.failure(t) }
    }
}
