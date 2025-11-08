package com.example.myapplication.data.groups.dao

import androidx.room.*
import com.example.myapplication.data.groups.model.LocalGroup
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: LocalGroup)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroups(groups: List<LocalGroup>)

    @Query("SELECT * FROM local_groups ORDER BY createdAt DESC")
    fun getAllGroupsFlow(): Flow<List<LocalGroup>>

    @Query("SELECT * FROM local_groups WHERE groupId = :groupId")
    fun getGroupByIdFlow(groupId: String): Flow<LocalGroup?>

    @Query("DELETE FROM local_groups WHERE groupId NOT IN (:ids)")
    suspend fun deleteGroupsNotIn(ids: List<String>)

    @Delete
    suspend fun deleteGroup(group: LocalGroup)

    @Query("DELETE FROM local_groups")
    suspend fun deleteAllGroups()

    @Query("DELETE FROM local_groups WHERE groupId = :groupId")
    suspend fun deleteGroupById(groupId: String)

    @Query("UPDATE local_groups SET isMember = :isMember WHERE groupId = :groupId")
    suspend fun updateMembership(groupId: String, isMember: Boolean)
}
