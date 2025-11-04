package com.example.myapplication.ui.community.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.community.model.Community
import com.example.myapplication.data.community.repository.CommunityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch


// ViewModel for managing Community data in the UI.

class CommunityViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CommunityRepository.getInstance(application)

    //  OBSERVE DATA
    // Get specific community by ID as Flow

    fun observeCommunity(communityId: String): Flow<Community?> {
        return repository.observeCommunityById(communityId)
    }

    // Search communities
    fun searchCommunities(query: String): Flow<List<Community>> {
        return repository.searchCommunities(query)
    }

    // Observe all communities as Flow (if needed by UI)
    fun observeAllCommunities(): Flow<List<Community>> = repository.observeAllCommunities()

    // Unified observe: My communities (joined + owned)
    fun observeMyCommunities(): Flow<List<Community>> = repository.observeMyCommunities()


    // Save community after API call (e.g., after createCommunity API)
    fun saveCommunity(community: Community) {
        viewModelScope.launch {
            repository.saveCommunity(community)
        }
    }

    // Create and save community from API response
    // Call this in  Fragment after successful API call
    fun createCommunity(
        communityId: String,
        name: String,
        description: String?,
        profilePicUrl: String? = null,
        profilePicLocalPath: String? = null,
        category: String? = null,
        isPrivate: Boolean = false,
        creatorId: String? = null,
        creatorName: String? = null,
        onComplete: (Community) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                val community = repository.createCommunity(
                    communityId = communityId,
                    name = name,
                    description = description,
                    profilePicUrl = profilePicUrl,
                    profilePicLocalPath = profilePicLocalPath,
                    category = category,
                    isPrivate = isPrivate,
                    creatorId = creatorId,
                    creatorName = creatorName
                )
                onComplete(community)
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    // Update community details
    fun updateCommunityDetails(communityId: String, name: String, description: String?) {
        viewModelScope.launch {
            repository.updateCommunityDetails(communityId, name, description)
        }
    }

    //Update community profile picture
    fun updateCommunityProfilePic(communityId: String, url: String?, localPath: String?) {
        viewModelScope.launch {
            repository.updateCommunityProfilePic(communityId, url, localPath)
        }
    }

    // Update member count
    fun updateMemberCount(communityId: String, count: Int) {
        viewModelScope.launch {
            repository.updateMemberCount(communityId, count)
        }
    }

    // Delete a community
    fun deleteCommunity(communityId: String) {
        viewModelScope.launch {
            repository.deleteCommunity(communityId)
        }
    }

    // Delete all communities (e.g., on logout)
    fun deleteAllCommunities() {
        viewModelScope.launch {
            repository.deleteAllCommunities()
        }
    }

    // Get specific community once (suspend function for coroutines)
    suspend fun getCommunityById(communityId: String): Community? {
        return repository.getCommunityById(communityId)
    }
}
