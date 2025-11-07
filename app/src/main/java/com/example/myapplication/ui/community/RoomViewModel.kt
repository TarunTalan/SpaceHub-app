package com.example.myapplication.ui.community

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.community.repository.CommunityRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel to resolve a community room's stable identifier (roomCode) by preferring local cache
 * and falling back to network if missing. Runs in viewModelScope to survive configuration changes.
 */
class RoomViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CommunityRepository.getInstance(app)

    private val _resolvedRoomCode = MutableStateFlow<String?>(null)
    val resolvedRoomCode: StateFlow<String?> = _resolvedRoomCode

    private val _chatRooms = MutableStateFlow<List<com.example.myapplication.data.chat_room.model.DataChatRoom>>(emptyList())
    val chatRooms: StateFlow<List<com.example.myapplication.data.chat_room.model.DataChatRoom>> = _chatRooms.asStateFlow()

    // Start resolving roomCode for given communityId/roomId
    fun startResolve(communityId: String, roomId: String) {
        viewModelScope.launch {
            try {
                // Try local first
                val local = try { repo.getLocalRooms(communityId) } catch (_: Exception) { emptyList() }
                val localMatch = local.firstOrNull { r -> r.id == roomId || r.roomCode == roomId || r.name == roomId }
                if (localMatch != null && localMatch.roomCode.isNotBlank()) {
                    _resolvedRoomCode.value = localMatch.roomCode
                    return@launch
                }

                // Fallback network (repo performs retries)
                val remoteRes = try { repo.getAllRooms(communityId) } catch (_: Exception) { Result.failure<List<com.example.myapplication.data.community.model.DataRoom>>(Exception("network-failure")) }
                val list = remoteRes.getOrNull() ?: emptyList()
                val match = list.firstOrNull { r -> r.id == roomId || r.roomCode == roomId || r.name == roomId }
                if (match != null && match.roomCode.isNotBlank()) {
                    _resolvedRoomCode.value = match.roomCode
                } else {
                    _resolvedRoomCode.value = null
                }
            } catch (_: Exception) {
                _resolvedRoomCode.value = null
            }
        }
    }

    // Load chat rooms for the community and expose via StateFlow
    fun loadChatRoomsForCommunity(communityId: String, roomId: String) {
        viewModelScope.launch {
            try {
                // Fetch all rooms for the community (typed DataRoom includes nested newChatRooms)
                android.util.Log.d("RoomViewModel", "loadChatRoomsForCommunity: requesting community rooms for communityId=$communityId, lookupKey=$roomId")
                val res = withContext(Dispatchers.IO) { repo.getAllRooms(communityId) }
                val rooms = res.getOrNull() ?: emptyList()

                // Find the matching parent room by id/roomCode/name
                val match = rooms.firstOrNull { r -> r.id == roomId || r.roomCode == roomId || r.name == roomId }
                if (match != null) {
                    // Expose nested chat rooms (newChatRooms) to UI
                    android.util.Log.d("RoomViewModel", "Found parent room in community response: id=${match.id}, roomCode=${match.roomCode}, newChatRooms=${match.newChatRooms?.size ?: 0}")
                    _chatRooms.value = match.newChatRooms ?: emptyList()
                    return@launch
                }

                // As a fallback, load user's chat rooms (global) to avoid empty UI
                android.util.Log.d("RoomViewModel", "Parent room not found in community rooms; falling back to getAllChatRooms (global)")
                val globalRes = withContext(Dispatchers.IO) { repo.getAllChatRooms() }
                _chatRooms.value = globalRes.getOrNull() ?: emptyList()
            } catch (t: Throwable) {
                android.util.Log.w("RoomViewModel", "loadChatRoomsForCommunity failed: ${t.message}")
            }
        }
    }
}
