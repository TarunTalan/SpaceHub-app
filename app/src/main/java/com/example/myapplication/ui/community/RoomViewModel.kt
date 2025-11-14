package com.example.myapplication.ui.community

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.community.repository.CommunityRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RoomViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CommunityRepository.getInstance(app)

    private val _resolvedRoomCode = MutableStateFlow<String?>(null)
    val resolvedRoomCode: StateFlow<String?> = _resolvedRoomCode

    private val _chatRooms = MutableStateFlow<List<com.example.myapplication.data.chat_room.model.DataChatRoom>>(emptyList())
    val chatRooms: StateFlow<List<com.example.myapplication.data.chat_room.model.DataChatRoom>> = _chatRooms.asStateFlow()

    private val _loading = MutableLiveData<Boolean>(false)
    val loading: LiveData<Boolean> = _loading

    // Start resolving roomCode for given communityId/roomId
    fun startResolve(communityId: String, roomId: String) {
        viewModelScope.launch {
            try {
                // Try local first only; do not perform remote fetch here to avoid duplicate network calls.
                val local = try { repo.getLocalRooms(communityId) } catch (_: Exception) { emptyList() }
                val localMatch = local.firstOrNull { r -> r.id == roomId || r.roomCode == roomId || r.name == roomId }
                if (localMatch != null && localMatch.roomCode.isNotBlank()) {
                    _resolvedRoomCode.value = localMatch.roomCode
                    return@launch
                }

                // If not found locally, leave resolvedRoomCode as null. The Fragment will trigger a network fetch
                // via loadChatRoomsForCommunity which will perform the remote call once.
                _resolvedRoomCode.value = null
            } catch (_: Exception) {
                _resolvedRoomCode.value = null
            }
        }
    }

    // Load chat rooms for the community and expose via StateFlow
    fun loadChatRoomsForCommunity(communityId: String, roomId: String) {
        viewModelScope.launch {
            try { _loading.postValue(true) } catch (_: Exception) {}
            try {
                // Fetch all rooms for the community (typed DataRoom includes nested newChatRooms)
                android.util.Log.d("RoomViewModel", "loadChatRoomsForCommunity: requesting community rooms for communityId=$communityId, lookupKey=$roomId")
                val res = withContext(Dispatchers.IO) { repo.getAllRooms(communityId) }
                val rooms = res.getOrNull() ?: emptyList()

                // Find the matching parent room by id/roomCode/name
                val match = rooms.firstOrNull { r -> r.id == roomId || r.roomCode == roomId || r.name == roomId }
                if (match != null) {
                    // If server provided nested chat rooms in `newChatRooms`, use them
                    val nested = match.newChatRooms
                    android.util.Log.d("RoomViewModel", "Found parent room in community response: id=${'$'}{match.id}, roomCode=${'$'}{match.roomCode}, newChatRooms=${'$'}{nested?.size ?: 0}")
                    if (!nested.isNullOrEmpty()) {
                        _chatRooms.value = nested
                        return@launch
                    }

                    // Otherwise try the dedicated summary endpoint (by roomCode)
                    val lookupCode = match.roomCode.ifBlank { match.id }
                    android.util.Log.d("RoomViewModel", "Attempting getChatRoomSummary for roomCode=$lookupCode")
                    val summaryRes = withContext(Dispatchers.IO) { repo.getChatRoomSummary(lookupCode) }
                    if (summaryRes.isSuccess) {
                        _chatRooms.value = summaryRes.getOrDefault(emptyList())
                        return@launch
                    } else {
                        android.util.Log.w("RoomViewModel", "getChatRoomSummary failed: ${'$'}{summaryRes.exceptionOrNull()?.message}")
                    }
                }

                // As a fallback, directly request the chat-room summary for the requested roomId
                val summaryRes = withContext(Dispatchers.IO) { repo.getChatRoomSummary(roomId) }
                if (summaryRes.isSuccess) {
                    _chatRooms.value = summaryRes.getOrDefault(emptyList())
                } else {
                }
            } catch (_: Throwable) {
                android.util.Log.w("RoomViewModel", "loadChatRoomsForCommunity failed")
            } finally {
                try { _loading.postValue(false) } catch (_: Exception) {}
            }
        }
    }
}
