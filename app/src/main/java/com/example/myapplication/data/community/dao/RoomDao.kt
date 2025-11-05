package com.example.myapplication.data.community.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.community.model.RoomEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RoomDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<RoomEntity>)

    @Query("DELETE FROM rooms WHERE communityId = :communityId")
    suspend fun deleteByCommunity(communityId: String)

    @Query("SELECT * FROM rooms WHERE communityId = :communityId ORDER BY name ASC")
    fun observeRooms(communityId: String): Flow<List<RoomEntity>>

    @Query("SELECT * FROM rooms WHERE communityId = :communityId ORDER BY name ASC")
    suspend fun getRooms(communityId: String): List<RoomEntity>
}

