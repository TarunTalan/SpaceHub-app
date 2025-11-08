package com.example.myapplication.ui.group.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.chat_room.model.DataChatRoom
import com.example.myapplication.data.groups.repository.LocalGroupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel to load and manage chat rooms inside a local group (uses LocalGroupRepository).
 */
class GroupRoomViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = LocalGroupRepository.getInstance(app.applicationContext)

    private val _chatRooms = MutableStateFlow<List<DataChatRoom>>(emptyList())
    val chatRooms: StateFlow<List<DataChatRoom>> = _chatRooms.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun loadChatRoomsForGroup(groupRoomCode: String) {
        viewModelScope.launch {
            try {
                _loading.value = true
                val res = withContext(Dispatchers.IO) { repo.getGroupChatRooms(groupRoomCode) }
                _chatRooms.value = res.getOrDefault(emptyList())
            } catch (t: Throwable) {
                _chatRooms.value = emptyList()
            } finally {
                _loading.value = false
            }
        }
    }

    fun createChatRoom(groupRoomCode: String, chatRoomName: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _loading.value = true
            val res = withContext(Dispatchers.IO) { repo.createChatRoomInGroup(groupRoomCode, chatRoomName) }
            if (res.isSuccess) {
                // reload list
                val listRes = withContext(Dispatchers.IO) { repo.getGroupChatRooms(groupRoomCode) }
                _chatRooms.value = listRes.getOrDefault(_chatRooms.value)
                onDone(true)
            } else {
                onDone(false)
            }
            _loading.value = false
        }
    }
}

