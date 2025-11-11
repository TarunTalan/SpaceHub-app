package com.example.myapplication.data.voice

import android.content.Context
import com.example.myapplication.data.network.NetworkModule
import com.example.myapplication.data.voice.model.CreateVoiceRoomResponse
import com.example.myapplication.data.voice.model.GetAllVoiceRoomsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Replace the previous placeholder repository with a network-backed implementation.
class VoiceRoomRepository private constructor(private val context: Context) {
    private val api = NetworkModule.createApiService(context)

    companion object {
        @Volatile
        private var instance: VoiceRoomRepository? = null
        fun getInstance(context: Context): VoiceRoomRepository {
            val appCtx = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: VoiceRoomRepository(appCtx).also { instance = it }
            }
        }
    }

    suspend fun createVoiceRoom(chatRoomId: String, roomName: String, createdBy: String): Result<CreateVoiceRoomResponse> =
        withContext(Dispatchers.IO) {
            try {
                val resp = api.createVoiceRoom(chatRoomId = chatRoomId, roomName = roomName, createdBy = createdBy)
                if (resp.isSuccessful) {
                    val body = resp.body()
                    if (body != null) Result.success(body) else Result.failure(Exception("Empty body"))
                } else {
                    Result.failure(Exception("HTTP ${resp.code()}: ${resp.message()}"))
                }
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    suspend fun getVoiceRooms(roomId: String): Result<GetAllVoiceRoomsResponse> = withContext(Dispatchers.IO) {
        try {
            val resp = api.getVoiceRooms(roomId)
            if (resp.isSuccessful) {
                val body = resp.body()
                if (body != null) Result.success(body) else Result.failure(Exception("Empty body"))
            } else {
                Result.failure(Exception("HTTP ${resp.code()}: ${resp.message()}"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    // Join a voice room via the server Janus join endpoint
    suspend fun joinVoiceRoom(janusRoomId: Int, displayName: String): Result<com.example.myapplication.data.voice.model.JoinVoiceRoomResponse> =
        withContext(Dispatchers.IO) {
            try {
                val resp = api.joinVoiceRoom(janusRoomId = janusRoomId, displayName = displayName)
                if (resp.isSuccessful) {
                    val body = resp.body()
                    if (body != null) Result.success(body) else Result.failure(Exception("Empty body"))
                } else {
                    Result.failure(Exception("HTTP ${resp.code()}: ${resp.message()}"))
                }
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
}
