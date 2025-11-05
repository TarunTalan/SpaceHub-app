package com.example.myapplication.ui.community.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.community.model.PendingRequest
import com.example.myapplication.data.community.repository.CommunityRepository
import kotlinx.coroutines.launch

class RequestsInboxViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CommunityRepository.getInstance(app)

    private val _requests = MutableLiveData<List<PendingRequest>>(emptyList())
    val requests: LiveData<List<PendingRequest>> = _requests

    private val _loading = MutableLiveData<Boolean>(false)
    val loading: LiveData<Boolean> = _loading

    private val _toast = MutableLiveData<String?>()
    val toast: LiveData<String?> = _toast

    fun loadRequests() {
        viewModelScope.launch {
            _loading.postValue(true)
            val result = repo.getMyPendingRequests()
            result.onSuccess { list ->
                _requests.postValue(list)
            }.onFailure { error ->
                _toast.postValue("Failed to load requests: ${error.message}")
            }
            _loading.postValue(false)
        }
    }

    fun acceptRequest(request: PendingRequest) {
        viewModelScope.launch {
            // TODO: Implement accept API call
            // For now, just remove from list
            _toast.postValue("Accept functionality will be implemented soon")
            val updatedList = _requests.value?.filter { it.id != request.id } ?: emptyList()
            _requests.postValue(updatedList)
        }
    }

    fun rejectRequest(request: PendingRequest) {
        viewModelScope.launch {
            // TODO: Implement reject API call
            // For now, just remove from list
            _toast.postValue("Reject functionality will be implemented soon")
            val updatedList = _requests.value?.filter { it.id != request.id } ?: emptyList()
            _requests.postValue(updatedList)
        }
    }
}

