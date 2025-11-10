package com.example.myapplication.ui.group

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.groups.repository.LocalGroupRepository
import com.example.myapplication.data.groups.model.DataX
import com.example.myapplication.data.groups.database.GroupsDatabase
import com.example.myapplication.data.groups.model.LocalGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class LocalGroupsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = LocalGroupRepository.getInstance(app.applicationContext)

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _groups = MutableStateFlow<List<DataX>>(emptyList())
    val groups: StateFlow<List<DataX>> = _groups

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val groupDao = GroupsDatabase.getInstance(app.applicationContext).groupDao()

    init {
        // Observe DB immediately so UI shows cached groups without waiting for network
        viewModelScope.launch {
            try {
                // Use catch to convert any upstream exceptions (no such table, db closed) into an empty list
                groupDao.getAllGroupsFlow()
                    .catch { t ->
                        android.util.Log.e("LocalGroupsVM", "DB flow error", t)
                        _error.value = t.message
                        emit(emptyList())
                    }
                    .collectLatest { list ->
                        try {
                            val mapped = list.map { entity -> localGroupToDataX(entity) }
                            android.util.Log.d("LocalGroupsVM", "DB emitted ${mapped.size} groups")
                            _groups.value = mapped
                        } catch (t: Throwable) {
                            _error.value = t.message
                        }
                    }
            } catch (t: Throwable) {
                android.util.Log.e("LocalGroupsVM", "Failed to start DB collector", t)
                _error.value = t.message
            }
        }
    }

    private fun localGroupToDataX(entity: LocalGroup): DataX {
        return DataX(
            chatRoomCode = entity.chatRoomCode ?: "",
            createdAt = entity.createdAt ?: "",
            createdByEmail = entity.createdByEmail ?: "",
            description = entity.description ?: "",
            id = entity.groupId,
            imageUrl = entity.imageUrl ?: "",
            memberEmails = entity.memberEmails,
            name = entity.name,
            totalMembers = entity.memberCount,
            updatedAt = entity.updatedAt ?: ""
        )
    }

    fun loadGroups() {
        viewModelScope.launch {
            _loading.value = true
            val res = repo.getAllLocalGroups()
            if (res.isSuccess) {
                _groups.value = res.getOrDefault(emptyList())
                _error.value = null
            } else {
                _error.value = res.exceptionOrNull()?.message
            }
            _loading.value = false
        }
    }

    @Suppress("unused")
    fun createGroup(nameBody: okhttp3.RequestBody, descBody: okhttp3.RequestBody, image: okhttp3.MultipartBody.Part? = null, onDone: (Boolean)->Unit = {}) {
        viewModelScope.launch {
            _loading.value = true
            val res = repo.createLocalGroup(nameBody, descBody, image)
            _loading.value = false
            onDone(res.isSuccess)
        }
    }
}
