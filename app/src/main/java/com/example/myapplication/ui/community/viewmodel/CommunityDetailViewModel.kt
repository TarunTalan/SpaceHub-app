package com.example.myapplication.ui.community.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.community.model.DataRoom
import com.example.myapplication.data.community.repository.CommunityRepository
import kotlinx.coroutines.launch

class CommunityDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CommunityRepository.getInstance(app)

    private val _communityId = MutableLiveData<String>()
    private val _communityName = MutableLiveData<String?>()

    private val _totalMembers = MutableLiveData<Int>(0)
    val totalMembers: LiveData<Int> = _totalMembers

    private val _loading = MutableLiveData<Boolean>(false)
    val loading: LiveData<Boolean> = _loading

    private val _toast = MutableLiveData<String?>()
    val toast: LiveData<String?> = _toast

    private val _rooms = MutableLiveData<List<DataRoom>>(emptyList())
    val rooms: LiveData<List<DataRoom>> = _rooms

    private val _deleted = MutableLiveData<Boolean>(false)
    val deleted: LiveData<Boolean> = _deleted

    fun setCommunityId(id: String) {
        if (_communityId.value == id) return
        _communityId.value = id
        viewModelScope.launch {
            repo.getCommunityById(id)?.let { _communityName.postValue(it.name) }
            refreshMemberCount()
            loadRooms()
        }
    }

    private suspend fun refreshMemberCount() {
        val id = _communityId.value ?: return
        val res = repo.fetchMemberCount(id)
        res.onSuccess { _totalMembers.postValue(it) }
            .onFailure { _toast.postValue(it.message) }
    }

    suspend fun createRoom(roomName: String) {
        val id = _communityId.value ?: return
        _loading.postValue(true)
        val res = repo.createRoom(id, roomName)
        _loading.postValue(false)
        res.onSuccess {
            _toast.postValue("Room created")
            refreshMemberCount()
            loadRooms()
        }.onFailure { _toast.postValue(it.message ?: "Failed to create room") }
    }

    suspend fun deleteRoom(roomId: String) {
        val id = _communityId.value ?: return
        _loading.postValue(true)
        val res = repo.deleteRoom(id, roomId)
        _loading.postValue(false)
        res.onSuccess {
            _toast.postValue("Room deleted")
            refreshMemberCount()
            loadRooms()
        }.onFailure { _toast.postValue(it.message ?: "Failed to delete room") }
    }

    suspend fun loadRooms() {
        val id = _communityId.value ?: return
        _loading.postValue(true)
        val res = repo.getAllRooms(id)
        _loading.postValue(false)
        res.onSuccess { list -> _rooms.postValue(list) }
            .onFailure { _toast.postValue(it.message ?: "Failed to load rooms") }
    }

    suspend fun renameRoom(roomId: String, newName: String) {
        val id = _communityId.value ?: return
        _loading.postValue(true)
        val res = repo.renameRoom(id, roomId, newName)
        _loading.postValue(false)
        res.onSuccess {
            _toast.postValue("Room renamed")
            refreshMemberCount()
            loadRooms()
        }.onFailure { _toast.postValue(it.message ?: "Failed to rename room") }
    }

    fun deleteCommunity() {
        val id = _communityId.value ?: return
        viewModelScope.launch {
            _loading.postValue(true)
            val res: Result<Unit> = repo.deleteCommunityRemote(id)
            _loading.postValue(false)
            res.onSuccess {
                _deleted.postValue(true)
                _toast.postValue("Community deleted")
            }.onFailure { err ->
                val msg = when (err) {
                    is IllegalAccessException -> "Only admins can delete community"
                    else -> err.message ?: "Failed to delete community"
                }
                _toast.postValue(msg)
            }
        }
    }
}
