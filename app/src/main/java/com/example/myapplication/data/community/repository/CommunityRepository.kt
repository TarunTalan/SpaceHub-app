package com.example.myapplication.data.community.repository

import android.content.Context
import com.example.myapplication.data.community.dao.CommunityDao
import com.example.myapplication.data.community.database.CommunityDatabase
import com.example.myapplication.data.community.model.Community
import kotlinx.coroutines.flow.Flow

/**
 * Repository for Community data operations.
 * Provides a clean API for accessing communities from Room database.
 * Handles both local database and API calls (API integration to be added).
 */
class CommunityRepository private constructor(context: Context) {

    private val communityDao: CommunityDao = CommunityDatabase.getInstance(context).communityDao()

    companion object {
        @Volatile
        private var INSTANCE: CommunityRepository? = null

        fun getInstance(context: Context): CommunityRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CommunityRepository(context).also { INSTANCE = it }
            }
        }
    }

    // ==================== OBSERVE (Flow for reactive UI) ====================

    /**
     * Observe all communities (auto-updates UI)
     */
    fun observeAllCommunities(): Flow<List<Community>> {
        return communityDao.getAllCommunitiesFlow()
    }

    /**
     * Observe specific community by ID (auto-updates UI)
     */
    fun observeCommunityById(communityId: String): Flow<Community?> {
        return communityDao.getCommunityByIdFlow(communityId)
    }

    /**
     * Observe communities owned by current user
     */
    fun observeOwnedCommunities(): Flow<List<Community>> {
        return communityDao.getOwnedCommunitiesFlow()
    }

    /**
     * Observe communities where user is a member
     */
    fun observeJoinedCommunities(): Flow<List<Community>> {
        return communityDao.getJoinedCommunitiesFlow()
    }

    /**
     * Search communities by name or description
     */
    fun searchCommunities(query: String): Flow<List<Community>> {
        return communityDao.searchCommunitiesFlow(query)
    }

    // ==================== ONE-TIME FETCH ====================

    /**
     * Get specific community by ID once
     */
    suspend fun getCommunityById(communityId: String): Community? {
        return communityDao.getCommunityById(communityId)
    }

    // ==================== CREATE ====================

    /**
     * Save a new community (e.g., after creating via API)
     */
    suspend fun saveCommunity(community: Community) {
        communityDao.insertCommunity(community)
    }

    /**
     * Create community from API response
     * Call this after successful createCommunity API call
     */
    suspend fun createCommunity(
        communityId: String,
        name: String,
        description: String?,
        profilePicUrl: String? = null,
        profilePicLocalPath: String? = null,
        coverPhotoUrl: String? = null,
        coverPhotoLocalPath: String? = null,
        category: String? = null,
        isPrivate: Boolean = false,
        creatorId: String? = null,
        creatorName: String? = null
    ): Community {
        val community = Community(
            communityId = communityId,
            name = name,
            description = description,
            profilePicUrl = profilePicUrl,
            profilePicLocalPath = profilePicLocalPath,
            coverPhotoUrl = coverPhotoUrl,
            coverPhotoLocalPath = coverPhotoLocalPath,
            category = category,
            isPrivate = isPrivate,
            creatorId = creatorId,
            creatorName = creatorName,
            isOwner = true,
            isMember = true,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        communityDao.insertCommunity(community)
        return community
    }

    // ==================== UPDATE ====================

    /**
     * Update community details
     */
    suspend fun updateCommunityDetails(
        communityId: String,
        name: String,
        description: String?
    ) {
        communityDao.updateCommunityDetails(communityId, name, description)
    }

    /**
     * Update community profile picture
     */
    suspend fun updateCommunityProfilePic(
        communityId: String,
        url: String?,
        localPath: String?
    ) {
        communityDao.updateCommunityProfilePic(communityId, url, localPath)
    }

    /**
     * Update full community object
     */
    suspend fun updateCommunity(community: Community) {
        communityDao.updateCommunity(community)
    }

    /**
     * Update member count
     */
    suspend fun updateMemberCount(communityId: String, count: Int) {
        communityDao.updateMemberCount(communityId, count)
    }

    // ==================== DELETE ====================

    /**
     * Delete a community
     */
    suspend fun deleteCommunity(communityId: String) {
        communityDao.deleteCommunityById(communityId)
    }

    /**
     * Delete all communities (e.g., on logout)
     */
    suspend fun deleteAllCommunities() {
        communityDao.deleteAllCommunities()
    }
}

