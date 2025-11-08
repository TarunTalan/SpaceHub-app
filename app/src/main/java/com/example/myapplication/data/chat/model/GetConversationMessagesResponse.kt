package com.example.myapplication.data.chat.model

import com.google.gson.annotations.SerializedName

// Simple response wrapper for conversation messages. Adjust fields if your backend returns different names.
data class GetConversationMessagesResponse(
    val status: Int,
    val message: String?,
    val data: List<ConversationMessageDto>?
)

// DTO representing a message returned by the API
data class ConversationMessageDto(
    val id: String?,
    @SerializedName("senderEmail") val senderEmail: String,
    @SerializedName("receiverEmail") val receiverEmail: String,
    val content: String?,
    // timestamp may be returned as epoch milliseconds or ISO string — try to parse accordingly
    val timestamp: String?
)

