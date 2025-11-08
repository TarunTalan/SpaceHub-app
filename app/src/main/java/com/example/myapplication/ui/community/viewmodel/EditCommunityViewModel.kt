package com.example.myapplication.ui.community.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.community.repository.CommunityRepository
import kotlinx.coroutines.launch

class EditCommunityViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CommunityRepository.getInstance(app)

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private val _toast = MutableLiveData<String?>()
    val toast: LiveData<String?> = _toast

    fun update(communityId: String, name: String, description: String) {
        if (name.isBlank()) { _toast.value = "Name required"; return }
        viewModelScope.launch {
            _loading.postValue(true)
            val res = repo.updateCommunityInfoRemote(communityId, name.trim(), description.trim())
            _loading.postValue(false)
            res.onSuccess { _toast.postValue("Community updated") }
                .onFailure { _toast.postValue(it.message ?: "Failed to update community") }
        }
    }

    // Overload: update with optional banner image part. First update details, then upload banner if provided.
    fun update(communityId: String, name: String, description: String, imagePart: okhttp3.MultipartBody.Part?) {
        if (name.isBlank()) { _toast.value = "Name required"; return }
        viewModelScope.launch {
            _loading.postValue(true)
            try {
                val detailsRes = repo.updateCommunityInfoRemote(communityId, name.trim(), description.trim())
                if (detailsRes.isFailure) {
                    _loading.postValue(false)
                    _toast.postValue(detailsRes.exceptionOrNull()?.message ?: "Failed to update community")
                    return@launch
                }

                if (imagePart != null) {
                    val email = com.example.myapplication.data.user.UserDataManager.getInstance(getApplication()).getEmail()
                    if (email.isNullOrBlank()) {
                        _loading.postValue(false)
                        _toast.postValue("User email missing")
                        return@launch
                    }
                    val uploadRes = repo.uploadCommunityBannerRemote(communityId, email, imagePart)
                    if (uploadRes.isFailure) {
                        _loading.postValue(false)
                        _toast.postValue(uploadRes.exceptionOrNull()?.message ?: "Failed to upload banner")
                        return@launch
                    }
                    // Refresh remote communities to pick up updated banner/profile URLs into Room
                    runCatching { repo.fetchMyCommunitiesRemote(email) }
                }

                _loading.postValue(false)
                _toast.postValue("Community updated")
            } catch (t: Throwable) {
                _loading.postValue(false)
                _toast.postValue(t.message ?: "Failed to update community")
            }
        }
    }
}
