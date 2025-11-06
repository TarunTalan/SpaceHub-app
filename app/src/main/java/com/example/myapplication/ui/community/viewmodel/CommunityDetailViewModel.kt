package com.example.myapplication.ui.community.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.community.model.DataRoom
import com.example.myapplication.data.community.repository.CommunityRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

class CommunityDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CommunityRepository.getInstance(app)

    private val _communityId = MutableLiveData<String>()
    private val _communityName = MutableLiveData<String?>()

    private val _totalMembers = MutableLiveData<Int>(0)
    val totalMembers: LiveData<Int> = _totalMembers

    private val _adminCount = MutableLiveData<Int>(0)
    val adminCount: LiveData<Int> = _adminCount

    private val _loading = MutableLiveData<Boolean>(false)
    val loading: LiveData<Boolean> = _loading

    private val _toast = MutableLiveData<String?>()
    val toast: LiveData<String?> = _toast

    private val _rooms = MutableLiveData<List<DataRoom>>(emptyList())
    val rooms: LiveData<List<DataRoom>> = _rooms

    private val _deleted = MutableLiveData<Boolean>(false)
    val deleted: LiveData<Boolean> = _deleted

    private var roomsCollectJob: Job? = null

    fun setCommunityId(id: String) {
        if (_communityId.value == id) return
        _communityId.value = id
        // Observe rooms from DB so UI auto-refreshes
        roomsCollectJob?.cancel()
        roomsCollectJob = viewModelScope.launch {
            repo.observeRooms(id).collectLatest { entities ->
                val mapped = entities.map { e -> DataRoom(id = e.id, name = e.name, roomCode = e.roomCode) }
                _rooms.postValue(mapped)
            }
        }
        viewModelScope.launch {
            repo.getCommunityById(id)?.let { _communityName.postValue(it.name) }
            refreshMemberCount()
            refreshAdminCount()
            // initial network sync
            syncRooms()
        }
    }

    private suspend fun refreshMemberCount() {
        val id = _communityId.value ?: return
        val res = repo.fetchMemberCount(id)
        res.onSuccess { _totalMembers.postValue(it) }
            .onFailure { _toast.postValue(it.message) }
    }

    private suspend fun refreshAdminCount() {
        val id = _communityId.value ?: return
        val res = repo.fetchMembers(id)
        res.onSuccess { list ->
            val count = list.count { m ->
                val role = m.role?.trim()?.uppercase()
                role?.contains("ADMIN") == true || role?.contains("OWNER") == true
            }
            _adminCount.postValue(count)
        }.onFailure { _toast.postValue(it.message) }
    }

    suspend fun createRoom(roomName: String) {
        val id = _communityId.value ?: return
        _loading.postValue(true)
        val res = repo.createRoom(id, roomName)
        _loading.postValue(false)
        res.onSuccess {
            _toast.postValue("Room created")
            syncRooms()
        }.onFailure { _toast.postValue(it.message ?: "Failed to create room") }
    }

    suspend fun deleteRoom(roomId: String) {
        val id = _communityId.value ?: return
        _loading.postValue(true)
        val res = repo.deleteRoom(id, roomId)
        _loading.postValue(false)
        res.onSuccess {
            _toast.postValue("Room deleted")
            syncRooms()
        }.onFailure { _toast.postValue(it.message ?: "Failed to delete room") }
    }

    private suspend fun syncRooms() {
        val id = _communityId.value ?: return
        _loading.postValue(true)
        val res = repo.refreshRooms(id)
        _loading.postValue(false)
        res.onFailure { err ->
            Log.w("CommunityVM", "refreshRooms failed for id=$id: ${err.message}")
            _toast.postValue(err.message ?: "Failed to load rooms")
        }
    }

    // non-suspending helper for UI to request a refresh
    fun refreshRooms() {
        viewModelScope.launch { syncRooms() }
    }

    suspend fun renameRoom(roomId: String, newName: String) {
        val id = _communityId.value ?: return
        _loading.postValue(true)
        val res = repo.renameRoom(id, roomId, newName)
        _loading.postValue(false)
        res.onSuccess {
            _toast.postValue("Room renamed")
            syncRooms()
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
