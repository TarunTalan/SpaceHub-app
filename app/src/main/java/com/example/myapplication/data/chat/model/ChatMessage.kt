package com.example.myapplication.data.chat.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

/**
 * Room Entity representing a chat message stored locally.
 */
@Entity(tableName = "messages")
data class ChatMessage(
    @PrimaryKey
    @SerializedName("id")
    val id: String,

    // Conversation this message belongs to
    val conversationId: String,

    // Sender fields
    val senderId: String,
    val senderName: String? = null,
    val senderAvatar: String? = null,

    // Recipient
    val recipientId: String? = null,

    // Message content
    val content: String,

    val timestamp: Long = System.currentTimeMillis(),

    // Delivery / read status
    val status: MessageStatus = MessageStatus.SENDING,

    // Convenience flag used by UI
    val isFromMe: Boolean = false
)
