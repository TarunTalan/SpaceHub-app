package com.example.myapplication.ui.friends.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.friends.model.IncomingFriendRequestItem
import com.example.myapplication.data.friends.repository.FriendsRepository
import kotlinx.coroutines.launch

class FriendRequestsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = FriendsRepository.getInstance(app)

    private val _requests = MutableLiveData<List<IncomingFriendRequestItem>>(emptyList())
    val requests: LiveData<List<IncomingFriendRequestItem>> = _requests

    private val _loading = MutableLiveData<Boolean>(false)
    val loading: LiveData<Boolean> = _loading

    private val _toast = MutableLiveData<String?>()
    val toast: LiveData<String?> = _toast

    private val _processingComplete = MutableLiveData<String?>()
    val processingComplete: LiveData<String?> = _processingComplete

    fun loadRequests() {
        viewModelScope.launch {
            _loading.postValue(true)
            val result = repo.getIncomingRequests()
            result.onSuccess { list ->
                _requests.postValue(list)
            }.onFailure { e ->
                _toast.postValue(e.message ?: "Failed to load friend requests")
            }
            _loading.postValue(false)
        }
    }

    fun accept(item: IncomingFriendRequestItem) {
        viewModelScope.launch {
            val email = item.email
            if (email.isNullOrBlank()) {
                _toast.postValue("Invalid request: missing email")
                _processingComplete.postValue(item.id)
                return@launch
            }
            val res = repo.respondToFriendRequest(email, accept = true)
            res.onSuccess {
                _toast.postValue("Friend request accepted")
                _requests.postValue(_requests.value?.filter { it.id != item.id })
            }.onFailure { e ->
                _toast.postValue(e.message ?: "Failed to accept request")
            }
            _processingComplete.postValue(item.id)
        }
    }

    fun reject(item: IncomingFriendRequestItem) {
        viewModelScope.launch {
            val email = item.email
            if (email.isNullOrBlank()) {
                _toast.postValue("Invalid request: missing email")
                _processingComplete.postValue(item.id)
                return@launch
            }
            val res = repo.respondToFriendRequest(email, accept = false)
            res.onSuccess {
                _toast.postValue("Friend request rejected")
                _requests.postValue(_requests.value?.filter { it.id != item.id })
            }.onFailure { e ->
                _toast.postValue(e.message ?: "Failed to reject request")
            }
            _processingComplete.postValue(item.id)
        }
    }
}

