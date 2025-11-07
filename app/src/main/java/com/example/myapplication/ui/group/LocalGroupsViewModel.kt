package com.example.myapplication.ui.group

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.groups.repository.LocalGroupRepository
import com.example.myapplication.data.groups.model.DataX
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LocalGroupsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = LocalGroupRepository.getInstance(app.applicationContext)

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _groups = MutableStateFlow<List<DataX>>(emptyList())
    val groups: StateFlow<List<DataX>> = _groups

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

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

    fun createGroup(nameBody: okhttp3.RequestBody, descBody: okhttp3.RequestBody, image: okhttp3.MultipartBody.Part? = null, onDone: (Boolean)->Unit = {}) {
        viewModelScope.launch {
            _loading.value = true
            val res = repo.createLocalGroup(nameBody, descBody, image)
            _loading.value = false
            onDone(res.isSuccess)
        }
    }
}

