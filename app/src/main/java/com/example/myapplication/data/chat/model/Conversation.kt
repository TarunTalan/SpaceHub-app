package com.example.myapplication.data.chat.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey val id: String,
    val peerEmail: String?,
    val peerName: String?,
    val peerAvatar: String?,
    val lastMessage: String?,
    val lastMessageTime: Long = System.currentTimeMillis(),
    val unreadCount: Int = 0
)
