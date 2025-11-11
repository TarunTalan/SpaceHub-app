package com.example.myapplication.ui.voice

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.voice.VoiceRoomRepository
import com.example.myapplication.data.voice.model.CreateVoiceRoomResponse
import com.example.myapplication.data.voice.model.GetAllVoiceRoomsResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class VoiceRoomViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = VoiceRoomRepository.getInstance(application)

    private val _createState = MutableStateFlow<CreateState>(CreateState.Idle)
    val createState: StateFlow<CreateState> = _createState

    private val _listState = MutableStateFlow<ListState>(ListState.Idle)
    val listState: StateFlow<ListState> = _listState

    private val _joinState = MutableStateFlow<JoinState>(JoinState.Idle)
    val joinState: StateFlow<JoinState> = _joinState

    sealed class CreateState {
        object Idle : CreateState()
        object Loading : CreateState()
        data class Success(val resp: CreateVoiceRoomResponse) : CreateState()
        data class Error(val msg: String) : CreateState()
    }

    sealed class ListState {
        object Idle : ListState()
        object Loading : ListState()
        data class Success(val resp: GetAllVoiceRoomsResponse) : ListState()
        data class Error(val msg: String) : ListState()
    }

    sealed class JoinState {
        object Idle : JoinState()
        object Loading : JoinState()
        data class Success(val resp: com.example.myapplication.data.voice.model.JoinVoiceRoomResponse) : JoinState()
        data class Error(val msg: String) : JoinState()
    }

    fun createVoiceRoom(chatRoomId: String, roomName: String, createdBy: String) {
        viewModelScope.launch {
            _createState.value = CreateState.Loading
            val res = repo.createVoiceRoom(chatRoomId = chatRoomId, roomName = roomName, createdBy = createdBy)
            if (res.isSuccess) {
                _createState.value = CreateState.Success(res.getOrThrow())
            } else {
                _createState.value = CreateState.Error(res.exceptionOrNull()?.message ?: "Unknown error")
            }
        }
    }

    fun getVoiceRooms(roomId: String) {
        viewModelScope.launch {
            _listState.value = ListState.Loading
            val res = repo.getVoiceRooms(roomId)
            if (res.isSuccess) {
                _listState.value = ListState.Success(res.getOrThrow())
            } else {
                _listState.value = ListState.Error(res.exceptionOrNull()?.message ?: "Unknown error")
            }
        }
    }

    fun joinVoiceRoom(janusRoomId: Int, displayName: String) {
        viewModelScope.launch {
            _joinState.value = JoinState.Loading
            val res = repo.joinVoiceRoom(janusRoomId, displayName)
            if (res.isSuccess) {
                _joinState.value = JoinState.Success(res.getOrThrow())
            } else {
                _joinState.value = JoinState.Error(res.exceptionOrNull()?.message ?: "Unknown error")
            }
        }
    }
}
