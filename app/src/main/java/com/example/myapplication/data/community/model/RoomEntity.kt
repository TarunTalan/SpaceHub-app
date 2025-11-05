package com.example.myapplication.data.community.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "rooms",
    indices = [Index(value = ["communityId"])]
)
data class RoomEntity(
    @PrimaryKey val id: String,
    val communityId: String,
    val name: String,
    val roomCode: String,
    val updatedAt: Long = System.currentTimeMillis()
)

