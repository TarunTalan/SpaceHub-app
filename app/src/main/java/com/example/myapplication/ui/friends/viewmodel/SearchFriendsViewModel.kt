package com.example.myapplication.ui.friends.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.friends.model.UserSearchResult
import com.example.myapplication.data.friends.repository.FriendsRepository
import kotlinx.coroutines.launch

class SearchFriendsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = FriendsRepository.getInstance(app)

    private val _searchResults = MutableLiveData<List<UserSearchResult>>()
    val searchResults: LiveData<List<UserSearchResult>> = _searchResults

    private val _loading = MutableLiveData<Boolean>()
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _requestSent = MutableLiveData<String?>() // email of user request sent to
    val requestSent: LiveData<String?> = _requestSent

    fun searchUsers(query: String) {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }

        _loading.value = true
        _error.value = null

        viewModelScope.launch {
            val result = repository.searchUsers(query)
            _loading.value = false

            result.onSuccess { users ->
                _searchResults.value = users
            }.onFailure { e ->
                _error.value = e.message ?: "Search failed"
                _searchResults.value = emptyList()
            }
        }
    }

    fun sendFriendRequest(userEmail: String) {
        viewModelScope.launch {
            val result = repository.sendFriendRequest(userEmail)

            result.onSuccess {
                _requestSent.value = userEmail
                // Update the search results to mark this user as pending
                _searchResults.value = _searchResults.value?.map { user ->
                    if (user.email == userEmail) {
                        user.copy(isPending = true)
                    } else {
                        user
                    }
                }
            }.onFailure { e ->
                _error.value = e.message ?: "Failed to send friend request"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun clearRequestSent() {
        _requestSent.value = null
    }
}

