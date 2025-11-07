package com.example.myapplication.ui.dashboard

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.myapplication.BuildConfig
import com.example.myapplication.data.friends.model.Data
import com.example.myapplication.data.friends.repository.FriendsRepository
import kotlinx.coroutines.launch

class FriendsListViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = FriendsRepository.getInstance(app)

    private val _friends = MutableLiveData<List<Data>>(emptyList())
    val friends: LiveData<List<Data>> = _friends

    private val _loading = MutableLiveData<Boolean>(false)
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    fun loadFriends() {
        viewModelScope.launch {
            _loading.postValue(true)
            val res = repo.getFriendsList()
            res.onSuccess { list ->
                Log.d("FriendsListVM", "Loaded ${list.size} friends")
                Log.d("FriendsListVM", "BASE_URL = ${BuildConfig.BASE_URL}")
                list.forEachIndexed { index, friend ->
                    val raw = friend.avatarUrl
                    val normalized = raw?.trim()?.let { r ->
                        when {
                            r.isBlank() -> null
                            r.startsWith("http://", ignoreCase = true) ||
                            r.startsWith("https://", ignoreCase = true) -> r
                            else -> "${BuildConfig.BASE_URL.trimEnd('/')}/${r.trimStart('/')}"
                        }
                    }
                    Log.d("FriendsListVM", "Friend $index: ${friend.firstName} ${friend.lastName}")
                    Log.d("FriendsListVM", "  Raw avatarUrl: $raw")
                    Log.d("FriendsListVM", "  Normalized URL: $normalized")
                }
                _friends.postValue(list)
            }.onFailure { e ->
                Log.e("FriendsListVM", "Failed to load friends", e)
                _error.postValue(e.message ?: "Failed to load friends")
            }
            _loading.postValue(false)
        }
    }

    fun clearError() { _error.value = null }
}
