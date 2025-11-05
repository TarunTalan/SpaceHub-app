package com.example.myapplication.ui.community.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.community.repository.CommunityRepository
import kotlinx.coroutines.launch

class CommunityOverviewViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CommunityRepository.getInstance(app)

    private val _communityName = MutableLiveData<String>()
    val communityName: LiveData<String> = _communityName

    private val _description = MutableLiveData<String>()
    val description: LiveData<String> = _description

    private val _imageUrl = MutableLiveData<String?>()
    val imageUrl: LiveData<String?> = _imageUrl

    private val _bannerUrl = MutableLiveData<String?>()
    val bannerUrl: LiveData<String?> = _bannerUrl

    private val _memberCount = MutableLiveData<Int>(0)
    val memberCount: LiveData<Int> = _memberCount

    private val _adminCount = MutableLiveData<Int>(0)
    val adminCount: LiveData<Int> = _adminCount

    private val _loading = MutableLiveData<Boolean>(false)
    val loading: LiveData<Boolean> = _loading

    private val _toast = MutableLiveData<String?>()
    val toast: LiveData<String?> = _toast

    private val _requestInProgress = MutableLiveData<Boolean>(false)
    val requestInProgress: LiveData<Boolean> = _requestInProgress

    private val _requestSent = MutableLiveData<Boolean>(false)
    val requestSent: LiveData<Boolean> = _requestSent

    private var communityId: String = ""

    fun loadCommunity(id: String, initialName: String = "", initialImageUrl: String? = null, initialDescription: String = "") {
        if (communityId == id) return // already loaded
        communityId = id

        // Set initial data from args while loading
        if (initialName.isNotBlank()) _communityName.value = initialName
        if (!initialImageUrl.isNullOrBlank()) _imageUrl.value = initialImageUrl
        if (initialDescription.isNotBlank()) _description.value = initialDescription

        viewModelScope.launch {
            _loading.postValue(true)

            // Try to load from local DB first
            val localComm = repo.getCommunityById(id)
            if (localComm != null) {
                _communityName.postValue(localComm.name)
                _description.postValue(localComm.description ?: "")
                _imageUrl.postValue(localComm.profilePicUrl)
                _bannerUrl.postValue(localComm.coverPhotoUrl)
                _memberCount.postValue(localComm.memberCount)
            }

            // Fetch member count and admin count from API
            val memberRes = repo.fetchMemberCount(id)
            memberRes.onSuccess { count -> _memberCount.postValue(count) }

            val adminRes = repo.fetchMembers(id)
            adminRes.onSuccess { members ->
                val admins = members.count { m ->
                    val role = m.role.trim().uppercase()
                    role.contains("ADMIN") || role.contains("OWNER")
                }
                _adminCount.postValue(admins)
            }

            _loading.postValue(false)
        }
    }

    fun requestToJoin(communityName: String) {
        if (_requestInProgress.value == true || _requestSent.value == true) return
        viewModelScope.launch {
            _requestInProgress.postValue(true)
            val res = repo.requestToJoinCommunity(communityName)
            _requestInProgress.postValue(false)
            if (res.isSuccess) {
                _requestSent.postValue(true)
                _toast.postValue("Request sent successfully")
            } else {
                _toast.postValue(res.exceptionOrNull()?.message ?: "Failed to send request")
            }
        }
    }
}

