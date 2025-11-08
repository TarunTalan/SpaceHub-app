package com.example.myapplication.data.chat.repository

import android.content.Context
import android.util.Log
import com.example.myapplication.data.chat.db.ChatDao
import com.example.myapplication.data.chat.model.*
import com.example.myapplication.data.chat.websocket.DirectChatWebSocketService
import com.example.myapplication.data.user.UserDataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import java.time.Instant

class ChatRepository private constructor(
    context: Context,
    private val chatDao: ChatDao
) {

    private val webSocketService = DirectChatWebSocketService.getInstance(context)
    private val userDataManager = UserDataManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        @Volatile
        private var INSTANCE: ChatRepository? = null

        fun getInstance(context: Context, chatDao: ChatDao): ChatRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChatRepository(context.applicationContext, chatDao).also { INSTANCE = it }
            }
        }

        private const val TAG = "ChatRepository"
    }

    init {
        // Listen to WebSocket messages
        scope.launch {
            webSocketService.messages.collect { wsMessage ->
                handleIncomingMessage(wsMessage)
            }
        }
        // Listen for server-sent history payloads and persist them
        scope.launch {
            try {
                webSocketService.history.collect { hist ->
                    try {
                        // hist.messages is Array<DirectChatMessage>
                        restoreConversationFromHistory(hist.chatWith, hist.messages.toList())
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to restore history for ${hist.chatWith}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "History collector failed: ${e.message}")
            }
        }
    }

    val connectionState = webSocketService.connectionState

    /** Connect to direct chat websocket for a specific sender/receiver pair. */
    fun connectWebSocket(senderEmail: String, receiverEmail: String) {
        webSocketService.connect(senderEmail, receiverEmail)
    }

    fun disconnectWebSocket() {
        webSocketService.disconnect()
    }

    fun getMessagesForConversation(conversationId: String): Flow<List<ChatMessage>> {
        return chatDao.getMessagesForConversation(conversationId)
    }

    suspend fun sendMessage(
        recipientEmail: String,
        recipientName: String,
        content: String
    ): Result<ChatMessage> {
        return try {
            val myEmail = userDataManager.getEmail() ?: return Result.failure(IllegalStateException("Email not set"))

            // Get name from DataStore
            val firstName = userDataManager.firstNameFlow.first() ?: ""
            val lastName = userDataManager.lastNameFlow.first() ?: ""
            val myName = "$firstName $lastName".trim().ifBlank { myEmail }

            val conversationId = generateConversationId(myEmail, recipientEmail)

            val messageId = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis()

            // Get avatar URL
            val avatarUrl = userDataManager.profileImageUrlFlow.first()

            // Create message locally (SENDING)
            val message = ChatMessage(
                id = messageId,
                conversationId = conversationId,
                senderId = myEmail,
                senderName = myName,
                senderAvatar = avatarUrl,
                recipientId = recipientEmail,
                content = content,
                timestamp = timestamp,
                status = MessageStatus.SENDING,
                isFromMe = true
            )

            // Persist local message & conversation
            chatDao.insertMessage(message)

            val conversation = chatDao.getConversation(conversationId) ?: Conversation(
                id = conversationId,
                peerEmail = recipientEmail,
                peerName = recipientName,
                peerAvatar = null,
                lastMessage = content,
                lastMessageTime = timestamp,
                unreadCount = 0
            )
            chatDao.insertConversation(conversation.copy(
                lastMessage = content,
                lastMessageTime = timestamp
            ))

            // Send via WebSocket with client messageId so server can ACK
            val success = webSocketService.sendMessage(
                senderEmail = myEmail,
                receiverEmail = recipientEmail,
                content = content,
                messageId = messageId
            )

            // Update status to SENT or FAILED
            if (success) {
                chatDao.updateMessageStatus(messageId, MessageStatus.SENT)
            } else {
                chatDao.updateMessageStatus(messageId, MessageStatus.FAILED)
            }

            Result.success(message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            Result.failure(e)
        }
    }

    fun sendTypingIndicator(@Suppress("UNUSED_PARAMETER") recipientEmail: String) {
        // Direct WebSocket doesn't support typing indicators in this implementation
    }

    @Suppress("unused")
    suspend fun markMessageAsRead(messageId: String, @Suppress("UNUSED_PARAMETER") conversationId: String) {
        try {
            chatDao.updateMessageStatus(messageId, MessageStatus.READ)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark message as read", e)
        }
    }

    suspend fun markConversationAsRead(conversationId: String) {
        try {
            chatDao.markConversationAsRead(conversationId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark conversation as read", e)
        }
    }

    suspend fun getOrCreateConversation(peerEmail: String, peerName: String, peerAvatar: String?): Conversation {
        val myEmail = userDataManager.getEmail() ?: ""
        val conversationId = generateConversationId(myEmail, peerEmail)

        return chatDao.getConversation(conversationId) ?: Conversation(
            id = conversationId,
            peerEmail = peerEmail,
            peerName = peerName,
            peerAvatar = peerAvatar,
            lastMessage = null,
            lastMessageTime = System.currentTimeMillis(),
            unreadCount = 0
        ).also {
            chatDao.insertConversation(it)
        }
    }

    private suspend fun handleIncomingMessage(wsMessage: com.example.myapplication.data.chat.websocket.DirectChatMessage) {
        try {
            val conversationId = generateConversationId(wsMessage.senderEmail, wsMessage.receiverEmail)

            // Control frames (delivered/read) — update status using provided id or fallback to latest sent message
            val ctrl = wsMessage.type?.lowercase()?.trim()
            if (!ctrl.isNullOrBlank()) {
                val ackId = wsMessage.messageId?.takeIf { it.isNotBlank() } ?: wsMessage.id?.takeIf { it.isNotBlank() }
                if (!ackId.isNullOrBlank()) {
                    try {
                        when (ctrl) {
                            "delivered" -> chatDao.updateMessageStatus(ackId, MessageStatus.DELIVERED)
                            "read" -> chatDao.updateMessageStatus(ackId, MessageStatus.READ)
                        }
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update message status for id=$ackId", e)
                        return
                    }
                } else {
                    try {
                        val recent = chatDao.getMessagesList(conversationId).lastOrNull { it.isFromMe }
                        if (recent != null) {
                            when (ctrl) {
                                "delivered" -> chatDao.updateMessageStatus(recent.id, MessageStatus.DELIVERED)
                                "read" -> chatDao.updateMessageStatus(recent.id, MessageStatus.READ)
                            }
                            return
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Fallback failed for conversation $conversationId", e)
                    }
                }
            }

            val myEmail = userDataManager.getEmail()

            if (wsMessage.senderEmail.isBlank() || wsMessage.receiverEmail.isBlank()) return

            // Parse timestamp: try ISO Instant, then numeric epoch millis, otherwise fallback to now
            val timestamp = try {
                Instant.parse(wsMessage.timestamp).toEpochMilli()
            } catch (_: Exception) {
                try {
                    wsMessage.timestamp.toLong()
                } catch (_: Exception) {
                    System.currentTimeMillis()
                }
            }

            val isSentByMe = !myEmail.isNullOrBlank() && wsMessage.senderEmail.equals(myEmail, ignoreCase = true)
            val isAddressedToMe = !myEmail.isNullOrBlank() && wsMessage.receiverEmail.equals(myEmail, ignoreCase = true)
            val isFromMe = if (isAddressedToMe) false else isSentByMe

            // If this is an echo of a sent message, attempt to update the local record
            if (isFromMe && !myEmail.isNullOrBlank()) {
                val recentMessages = chatDao.getMessagesList(conversationId)
                val matched = recentMessages.firstOrNull { existing ->
                    existing.senderId.equals(wsMessage.senderEmail, ignoreCase = true) &&
                            existing.content == wsMessage.content &&
                            Math.abs(existing.timestamp - timestamp) < 2000
                }
                if (matched != null) {
                    try {
                        val ctrlType = wsMessage.type?.lowercase()?.trim()
                        when (ctrlType) {
                            "read" -> chatDao.updateMessageStatus(matched.id, MessageStatus.READ)
                            else -> chatDao.updateMessageStatus(matched.id, MessageStatus.DELIVERED)
                        }
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update status for echoed message ${matched.id}", e)
                    }
                }
            }

            val peerEmail = if (isFromMe) wsMessage.receiverEmail else wsMessage.senderEmail

            // Create and persist incoming message
            val message = ChatMessage(
                id = wsMessage.id ?: UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderId = wsMessage.senderEmail,
                senderName = wsMessage.senderEmail,
                senderAvatar = null,
                recipientId = wsMessage.receiverEmail,
                content = wsMessage.content,
                timestamp = timestamp,
                status = MessageStatus.DELIVERED,
                isFromMe = isFromMe
            )

            chatDao.insertMessage(message)

            // Update conversation metadata
            val conversation = chatDao.getConversation(conversationId)
            if (conversation != null) {
                chatDao.updateConversation(conversation.copy(
                    lastMessage = message.content,
                    lastMessageTime = message.timestamp,
                    unreadCount = if (isFromMe) conversation.unreadCount else conversation.unreadCount + 1
                ))
            } else {
                chatDao.insertConversation(Conversation(
                    id = conversationId,
                    peerEmail = peerEmail,
                    peerName = peerEmail,
                    peerAvatar = null,
                    lastMessage = message.content,
                    lastMessageTime = message.timestamp,
                    unreadCount = if (isFromMe) 0 else 1
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle incoming message", e)
        }
    }

    private fun generateConversationId(email1: String?, email2: String?): String {
        val e1 = email1?.trim() ?: ""
        val e2 = email2?.trim() ?: ""

        if (e1.isEmpty() || e2.isEmpty()) return "unknown_${System.currentTimeMillis()}"

        val sorted = listOf(e1, e2).sorted()
        return "${sorted[0]}_${sorted[1]}".replace("@", "_").replace(".", "_")
    }
}
