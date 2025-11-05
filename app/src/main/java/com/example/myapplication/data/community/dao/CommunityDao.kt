package com.example.myapplication.data.community.dao

import androidx.room.*
import com.example.myapplication.data.community.model.Community
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Community operations.
 * Provides reactive queries using Flow for automatic UI updates.
 */
@Dao
interface CommunityDao {

    // INSERT
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommunity(community: Community)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommunities(communities: List<Community>)

    // QUERY

     // Get all communities as Flow (auto-updates UI when data changes)
    @Query("SELECT * FROM communities ORDER BY createdAt DESC")
    fun getAllCommunitiesFlow(): Flow<List<Community>>

     // Get all communities as one-time fetch
    @Query("SELECT * FROM communities ORDER BY createdAt DESC")
    suspend fun getAllCommunities(): List<Community>

     // Get specific community by ID as Flow
    @Query("SELECT * FROM communities WHERE communityId = :communityId")
    fun getCommunityByIdFlow(communityId: String): Flow<Community?>

     // Get specific community by ID as one-time fetch
    @Query("SELECT * FROM communities WHERE communityId = :communityId")
    suspend fun getCommunityById(communityId: String): Community?

    // Unified query: communities where user is owner or member
    // Use this as the single source for "My communities" (includes both owned and joined)
    @Query("SELECT * FROM communities WHERE isOwner = 1 OR isMember = 1 ORDER BY createdAt DESC")
    fun getMyCommunitiesFlow(): Flow<List<Community>>

     // Search communities by name or description
    @Query("""
        SELECT * FROM communities 
        WHERE name LIKE '%' || :query || '%' 
        OR description LIKE '%' || :query || '%'
        ORDER BY createdAt DESC
    """)
    fun searchCommunitiesFlow(query: String): Flow<List<Community>>

    //  UPDATE

    // Full update of community object
    @Update
    suspend fun updateCommunity(community: Community)

     // Update specific fields without replacing entire object
    @Query("""
        UPDATE communities 
        SET name = :name, 
            description = :description,
            updatedAt = :updatedAt
        WHERE communityId = :communityId
    """)
    suspend fun updateCommunityDetails(
        communityId: String,
        name: String,
        description: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

     // Update community profile picture
    @Query("""
        UPDATE communities 
        SET profilePicUrl = :url,
            profilePicLocalPath = :localPath,
            updatedAt = :updatedAt
        WHERE communityId = :communityId
    """)
    suspend fun updateCommunityProfilePic(
        communityId: String,
        url: String?,
        localPath: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

     // Update member count
    @Query("UPDATE communities SET memberCount = :count WHERE communityId = :communityId")
    suspend fun updateMemberCount(communityId: String, count: Int)

    //  DELETE

    // Delete specific community
    @Delete
    suspend fun deleteCommunity(community: Community)

    // Delete community by ID
    @Query("DELETE FROM communities WHERE communityId = :communityId")
    suspend fun deleteCommunityById(communityId: String)

    // Delete all communities
    @Query("DELETE FROM communities")
    suspend fun deleteAllCommunities()

    // Delete communities not present in the latest server response
    @Query("DELETE FROM communities WHERE communityId NOT IN (:ids)")
    suspend fun deleteCommunitiesNotIn(ids: List<String>)

    // Delete all non-member/owner communities
    @Query("DELETE FROM communities WHERE isOwner = 0 AND isMember = 0")
    suspend fun deleteAllNonMyCommunities()
}
