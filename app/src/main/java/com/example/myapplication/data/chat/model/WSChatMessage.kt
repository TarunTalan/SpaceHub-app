package com.example.myapplication.data.chat.model

import com.google.gson.annotations.SerializedName

/**
 * WebSocket DTO used for STOMP messages exchanged over SockJS/WebSocket.
 * Fields are kept nullable to be tolerant to variations from server.
 */
data class WSChatMessage(
    val id: String? = null,
    val messageId: String? = null,
    val type: String? = null,
    val conversationId: String? = null,
    val senderId: String? = null,
    val senderEmail: String? = null,
    val receiverEmail: String? = null,
    val recipientId: String? = null,
    // Some servers use the key 'message' instead of 'content' — keep both fields to be tolerant
    val message: String? = null,
    val content: String? = null,
    // Temporary client-side id used when sending optimistic messages; server may echo it back
    val tempId: String? = null,
    // Whether message was optimistic/local echo
    val optimistic: Boolean? = null,
    // Additional optional metadata often present in incoming payloads
    val senderUsername: String? = null,
    val timestamp: String? = null
)
