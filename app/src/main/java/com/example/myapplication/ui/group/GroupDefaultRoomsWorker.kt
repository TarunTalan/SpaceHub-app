package com.example.myapplication.ui.group

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.Data
import androidx.work.ListenableWorker
import com.example.myapplication.data.groups.repository.LocalGroupRepository
import com.example.myapplication.data.user.UserDataManager
import com.example.myapplication.data.voice.VoiceRoomRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Worker to create default chat and voice rooms for a newly created local group.
 * Uses application context and does network calls on IO dispatcher.
 */
class GroupDefaultRoomsWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        const val INPUT_GROUP_ID = "group_id"
        const val OUTPUT_CHAT_CREATED = "chat_created"
        const val OUTPUT_VOICE_CREATED = "voice_created"
    }

    override suspend fun doWork(): ListenableWorker.Result {
        val gid = inputData.getString(INPUT_GROUP_ID)
        if (gid.isNullOrBlank()) return ListenableWorker.Result.failure()

        return try {
            // Run network ops on IO
            val pair = withContext(Dispatchers.IO) {
                try {
                    val repo = LocalGroupRepository.getInstance(applicationContext)
                    val voiceRepo = VoiceRoomRepository.getInstance(applicationContext)
                    val userData = UserDataManager.getInstance(applicationContext)

                    // Get authoritative details (may include chatRoomCode/room id)
                    val detailsRes = repo.getLocalGroupDetails(gid)
                    val details = detailsRes.getOrNull()
                    // Prefer chatRoomId returned by server (added to DTO). Fall back to chatRoomCode for backward compatibility then to id/gid
                    val chatRoomId = (details?.chatRoomId as? String)?.takeIf { it.isNotBlank() }
                        ?: (details?.chatRoomCode as? String)?.takeIf { it.isNotBlank() }
                        ?: (details?.id ?: gid)

                    var chatCreated = false
                    var voiceCreated = false

                    // Attempt to create default chat room (best-effort)
                    var createdChatCode: String? = null
                    try {
                        // Prefer the server-provided chatRoomCode for creating child chat rooms. Fall back to chatRoomId if missing.
                        val parentForChat = (details?.chatRoomCode as? String)?.takeIf { it.isNotBlank() } ?: chatRoomId
                        val chatRes = repo.createChatRoomInGroup(parentForChat, "General Chat")
                        if (chatRes.isSuccess) {
                            chatCreated = true
                            createdChatCode = chatRes.getOrNull()?.chatRoomCode ?: chatRes.getOrNull()?.id
                        }
                    } catch (t: Throwable) {
                        if (t is IOException) throw t
                    }

                    // Attempt to create default voice room (best-effort)
                    try {
                        val creator = try { userData.getEmail() } catch (_: Exception) { null } ?: ""
                        val voiceRes = voiceRepo.createVoiceRoom(chatRoomId, "General", creator)
                        if (voiceRes.isSuccess) {
                            voiceCreated = true
                            // Refresh authoritative group details and voice-room list so UI can observe updated data
                            try { repo.getLocalGroupDetails(gid) } catch (_: Exception) {}
                            try { voiceRepo.getVoiceRooms(chatRoomId) } catch (_: Exception) {}
                            // Send a broadcast so any active UI can refresh its voice-room list immediately
                            try {
                                val intent = android.content.Intent("com.example.myapplication.ACTION_DEFAULT_ROOMS_CREATED")
                                intent.putExtra("groupId", gid)
                                applicationContext.sendBroadcast(intent)
                            } catch (_: Exception) {}
                        }
                    } catch (t: Throwable) {
                        if (t is IOException) throw t
                    }

                    Pair(chatCreated, voiceCreated)
                } catch (t: Throwable) {
                    throw t
                }
            }

            val (chatCreated, voiceCreated) = pair
            val output = Data.Builder().putBoolean(OUTPUT_CHAT_CREATED, chatCreated).putBoolean(OUTPUT_VOICE_CREATED, voiceCreated).build()
            ListenableWorker.Result.success(output)
        } catch (t: Throwable) {
            return if (t is IOException) ListenableWorker.Result.retry() else ListenableWorker.Result.failure()
        }
    }
}
