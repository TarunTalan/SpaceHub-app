package com.example.myapplication.data.chat.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class ChatMessage(
    @PrimaryKey
    val id: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val senderAvatar: String?,
    val recipientId: String,
    val content: String,
    val timestamp: Long,
    val status: MessageStatus = MessageStatus.SENT,
    val type: MessageType = MessageType.TEXT,
    val isFromMe: Boolean = false
)

enum class MessageStatus {
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
}

enum class MessageType {
    TEXT,
    IMAGE,
    FILE,
    TYPING_INDICATOR
}

// WebSocket message format
data class WSChatMessage(
    val type: String, // "message", "typing", "delivered", "read"
    val messageId: String? = null,
    val conversationId: String,
    val senderId: String,
    val senderName: String? = null,
    val senderAvatar: String? = null,
    val recipientId: String,
    val content: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

// Conversation/Chat room
@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey
    val id: String,
    val peerEmail: String,
    val peerName: String,
    val peerAvatar: String?,
    val lastMessage: String?,
    val lastMessageTime: Long,
    val unreadCount: Int = 0
)

