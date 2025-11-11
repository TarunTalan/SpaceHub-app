package com.example.myapplication.ui.group.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.groups.model.DataXX
import com.example.myapplication.data.groups.model.DataXXX
import com.example.myapplication.data.groups.repository.LocalGroupRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ViewModel for local group details. Mirrors CommunityDetailViewModel but uses LocalGroupRepository.
 */
class GroupDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = LocalGroupRepository.getInstance(app.applicationContext)

    private val _groupId = MutableLiveData<String>()
    private val _group = MutableLiveData<DataXX?>(null)
    val group: LiveData<DataXX?> = _group

    private val _totalMembers = MutableLiveData<Int>(0)
    val totalMembers: LiveData<Int> = _totalMembers

    private val _loading = MutableLiveData<Boolean>(false)
    val loading: LiveData<Boolean> = _loading

    private val _toast = MutableLiveData<String?>(null)
    val toast: LiveData<String?> = _toast

    private val _members = MutableLiveData<List<DataXXX>>(emptyList())
    val members: LiveData<List<DataXXX>> = _members

    private val _deleted = MutableLiveData<Boolean>(false)
    val deleted: LiveData<Boolean> = _deleted

    private val _inviteData = MutableLiveData<com.example.myapplication.data.groups.model.DataXXXXX?>(null)
    val inviteData: LiveData<com.example.myapplication.data.groups.model.DataXXXXX?> = _inviteData

    fun setGroupId(id: String) {
        android.util.Log.d("GroupDetailVM", "setGroupId: requested id=$id current=${_groupId.value}")
        // Clear transient flags (deleted/toast) when switching to a different group
        _deleted.value = false
        _toast.value = null
        if (_groupId.value == id) {
            android.util.Log.d("GroupDetailVM", "setGroupId: same id, skipping reload")
            // If we have no cached group data for this id (e.g. VM was retained but fragment recreated),
            // trigger a refresh so the UI receives the required network calls.
            if (_group.value == null) {
                android.util.Log.d("GroupDetailVM", "setGroupId: same id but group empty — forcing refresh")
                refreshDetails()
                loadMembers()
            }
            return
        }
        _groupId.value = id
        // Load details + members
        refreshDetails()
        loadMembers()
    }

    fun refreshDetails() {
        val id = _groupId.value ?: return
        viewModelScope.launch {
            _loading.postValue(true)
            try {
                val res = withContext(Dispatchers.IO) { repo.getLocalGroupDetails(id) }
                _loading.postValue(false)
                if (res.isSuccess) {
                    val data = res.getOrNull()
                    _group.postValue(data)
                    _totalMembers.postValue(data?.totalMembers ?: 0)
                } else {
                    _toast.postValue(res.exceptionOrNull()?.message ?: "Failed to load group")
                }
            } catch (t: Throwable) {
                _loading.postValue(false)
                _toast.postValue(t.message ?: "Failed to load group")
            }
        }
    }

    fun loadMembers() {
        val id = _groupId.value ?: return
        viewModelScope.launch {
            _loading.postValue(true)
            try {
                val res = withContext(Dispatchers.IO) { repo.getLocalGroupMembers(id) }
                _loading.postValue(false)
                if (res.isSuccess) {
                    val list = res.getOrDefault(emptyList())
                    android.util.Log.d("GroupDetailVM", "loadMembers: fetched list size = ${list.size} for id=$id")
                    _members.postValue(list)
                    android.util.Log.d("GroupDetailVM", "loadMembers: posted members LiveData for id=$id")
                } else {
                    android.util.Log.w("GroupDetailVM", "loadMembers: failed for id=$id: ${res.exceptionOrNull()?.message}")
                    _toast.postValue(res.exceptionOrNull()?.message ?: "Failed to load members")
                }
            } catch (t: Throwable) {
                _loading.postValue(false)
                android.util.Log.e("GroupDetailVM", "loadMembers: exception for id=$id", t)
                _toast.postValue(t.message ?: "Failed to load members")
            }
        }
    }

    fun requestToJoin() {
        val id = _groupId.value ?: return
        viewModelScope.launch {
            _loading.postValue(true)
            try {
                val res = withContext(Dispatchers.IO) { repo.requestToJoinLocalGroup(id) }
                _loading.postValue(false)
                if (res.isSuccess) {
                    _toast.postValue("Request sent")
                } else {
                    _toast.postValue(res.exceptionOrNull()?.message ?: "Failed to send request")
                }
            } catch (t: Throwable) {
                _loading.postValue(false)
                _toast.postValue(t.message ?: "Failed to send request")
            }
        }
    }

    fun deleteGroup() {
        val id = _groupId.value ?: return
        viewModelScope.launch {
            _loading.postValue(true)
            try {
                val res = withContext(Dispatchers.IO) {
                    val email = com.example.myapplication.data.user.UserDataManager.getInstance(getApplication()).getEmail()
                    if (email == null) return@withContext Result.failure<Unit>(IllegalStateException("Email not set"))
                    repo.deleteLocalGroup(id)
                }
                _loading.postValue(false)
                if (res.isSuccess) {
                    _deleted.postValue(true)
                    _toast.postValue("Group deleted")
                } else {
                    _toast.postValue(res.exceptionOrNull()?.message ?: "Failed to delete group")
                }
            } catch (t: Throwable) {
                _loading.postValue(false)
                _toast.postValue(t.message ?: "Failed to delete group")
            }
        }
    }

    fun createInviteLink() {
        val id = _groupId.value ?: return
        viewModelScope.launch {
            _loading.postValue(true)
            try {
                val res = withContext(Dispatchers.IO) { repo.createLocalGroupInviteLink(id) }
                _loading.postValue(false)
                if (res.isSuccess) {
                    val data = res.getOrNull()
                    _inviteData.postValue(data)
                } else {
                    _toast.postValue(res.exceptionOrNull()?.message ?: "Failed to create invite link")
                }
            } catch (t: Throwable) {
                _loading.postValue(false)
                _toast.postValue(t.message ?: "Failed to create invite link")
            }
        }
    }

    // Helpers to clear transient LiveData after UI has consumed them
    fun clearToast() { _toast.value = null }
    fun clearDeleted() { _deleted.value = false }
    fun clearInviteData() { _inviteData.value = null }
}
