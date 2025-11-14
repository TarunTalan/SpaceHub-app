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

    // Track which communityIds have already had their initial network sync performed
    // This prevents duplicate getAllRooms / getAllMembers calls when fragment recreates
    // or setCommunityId is invoked multiple times for the same id.
    private val _syncedCommunities = mutableSetOf<String>()

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
    private var communityCollectJob: Job? = null

    fun setCommunityId(id: String) {
        // Always clear any transient toast from previous context to avoid showing stale messages
        _toast.postValue(null)
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
        // Observe community entity for memberCount / name updates
        communityCollectJob?.cancel()
        communityCollectJob = viewModelScope.launch {
            repo.observeCommunityById(id).collectLatest { comm ->
                comm?.let {
                    _communityName.postValue(it.name)
                    _totalMembers.postValue(it.memberCount)
                }
            }
        }
        viewModelScope.launch {
            repo.getCommunityById(id)?.let { _communityName.postValue(it.name) }
            // Perform the initial network sync for a given community only once per ViewModel instance.
            // This avoids duplicate getAllRooms/getAllMembers calls when fragment recreates or
            // setCommunityId is invoked multiple times for the same id.
            val firstTime = synchronized(_syncedCommunities) { _syncedCommunities.add(id) }
            if (firstTime) {
                try { refreshMemberCount() } catch (_: Exception) {}
                try { refreshAdminCount() } catch (_: Exception) {}
                try { syncRooms() } catch (_: Exception) {}
            }
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
            // Success: refresh rooms silently; UI will not show a success toast
            syncRooms()
        }.onFailure { _toast.postValue(it.message ?: "Failed to create room") }
    }

    suspend fun deleteRoom(roomId: String) {
        val id = _communityId.value ?: return
        _loading.postValue(true)
        val res = repo.deleteRoom(id, roomId)
        _loading.postValue(false)
        res.onSuccess {
            // Success: refresh rooms silently
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


    fun refreshDetails() {
        viewModelScope.launch {
            val id = _communityId.value
            if (!id.isNullOrBlank()) {
                try { repo.fetchMembers(id, force = true) } catch (_: Exception) {}
            }
            try {
                refreshMemberCount()
            } catch (_: Exception) {}
            try {
                refreshAdminCount()
            } catch (_: Exception) {}
            try {
                syncRooms()
            } catch (_: Exception) {}
        }
    }

    suspend fun renameRoom(roomId: String, newName: String) {
        val id = _communityId.value ?: return
        _loading.postValue(true)
        val res = repo.renameRoom(id, roomId, newName)
        _loading.postValue(false)
        res.onSuccess {
            // Success: refresh silently
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
                // Do not emit a success toast; UI handles navigation and can show its own messages if needed
            }.onFailure { err ->
                val msg = when (err) {
                    is IllegalAccessException -> "Only admins can delete community"
                    else -> err.message ?: "Failed to delete community"
                }
                _toast.postValue(msg)
            }
        }
    }

    // Clear transient UI toast so fragments don't show stale messages when re-attaching
    fun clearToast() { _toast.postValue(null) }
}
