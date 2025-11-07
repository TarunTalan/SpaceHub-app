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
        // Listen to WebSocket messages and save to database
        scope.launch {
            webSocketService.messages.collect { wsMessage ->
                handleIncomingMessage(wsMessage)
            }
        }
    }

    val connectionState = webSocketService.connectionState

    fun connectWebSocket() {
        webSocketService.connect()
    }

    fun disconnectWebSocket() {
        webSocketService.disconnect()
    }

    fun getMessagesForConversation(conversationId: String): Flow<List<ChatMessage>> {
        return chatDao.getMessagesForConversation(conversationId)
    }

    fun getAllConversations(): Flow<List<Conversation>> {
        return chatDao.getAllConversations()
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

            // Create message
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

            // Save to local database
            chatDao.insertMessage(message)

            // Update or create conversation
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

            // Send via WebSocket
            val success = webSocketService.sendMessage(
                senderEmail = myEmail,
                receiverEmail = recipientEmail,
                content = content
            )

            // Update status to sent
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
        // Direct WebSocket doesn't support typing indicators
        // This is a no-op for compatibility
    }

    suspend fun markMessageAsRead(messageId: String, @Suppress("UNUSED_PARAMETER") conversationId: String) {
        try {
            chatDao.updateMessageStatus(messageId, MessageStatus.READ)
            // Direct WebSocket doesn't send read receipts to server
        } catch (@Suppress("SwallowedException") e: Exception) {
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
            val myEmail = userDataManager.getEmail()

            // Validate email addresses
            if (wsMessage.senderEmail.isBlank() || wsMessage.receiverEmail.isBlank()) {
                Log.e(TAG, "Invalid message: sender='${wsMessage.senderEmail}', receiver='${wsMessage.receiverEmail}', content='${wsMessage.content}'")
                return
            }

            // Parse ISO timestamp to millis
            val timestamp = try {
                // Handle nanosecond precision timestamps (e.g., 2025-11-07T03:19:19.803047233)
                val truncated = if (wsMessage.timestamp.contains('.')) {
                    val parts = wsMessage.timestamp.split('.')
                    if (parts.size >= 2) {
                        // Extract fractional seconds part
                        val fractional = parts[1]
                        // Find where the timezone info starts (Z, +, or -)
                        val tzIndex = fractional.indexOfFirst { it == 'Z' || it == '+' || it == '-' }

                        if (tzIndex > 3) {
                            // Has more than millisecond precision, truncate to 3 digits
                            val millis = fractional.substring(0, 3)
                            val tz = if (tzIndex > 0) fractional.substring(tzIndex) else "Z"
                            "${parts[0]}.$millis$tz"
                        } else if (tzIndex == -1 && fractional.length > 3) {
                            // No timezone, but has extra precision
                            "${parts[0]}.${fractional.substring(0, 3)}Z"
                        } else {
                            // Already correct format or needs Z
                            if (wsMessage.timestamp.endsWith("Z") || wsMessage.timestamp.contains("+") || wsMessage.timestamp.contains("-")) {
                                wsMessage.timestamp
                            } else {
                                "${wsMessage.timestamp}Z"
                            }
                        }
                    } else {
                        wsMessage.timestamp
                    }
                } else {
                    // No fractional seconds, add Z if needed
                    if (wsMessage.timestamp.endsWith("Z")) wsMessage.timestamp else "${wsMessage.timestamp}Z"
                }

                Instant.parse(truncated).toEpochMilli()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse timestamp '${wsMessage.timestamp}', using current time. Error: ${e.message}")
                System.currentTimeMillis()
            }

            val conversationId = generateConversationId(wsMessage.senderEmail, wsMessage.receiverEmail)

            Log.d(TAG, "Processing message: id=${wsMessage.id}, conversationId=$conversationId, from=${wsMessage.senderEmail}, to=${wsMessage.receiverEmail}, myEmail=$myEmail")

            // Determine if this message is from me
            // IMPORTANT: A message can't be FROM me if it's addressed TO me
            val isSentByMe = !myEmail.isNullOrBlank() && wsMessage.senderEmail.equals(myEmail, ignoreCase = true)
            val isAddressedToMe = !myEmail.isNullOrBlank() && wsMessage.receiverEmail.equals(myEmail, ignoreCase = true)

            // Bulletproof logic: If message is addressed TO me, it's definitely incoming (not from me)
            // Even if sender somehow matches myEmail (shouldn't happen, but safety first)
            val isFromMe = if (isAddressedToMe) {
                false  // Message TO me is never FROM me
            } else {
                isSentByMe  // Only if not addressed to me, check if I sent it
            }

            Log.d(TAG, "Message isFromMe: $isFromMe (sender=${wsMessage.senderEmail}, receiver=${wsMessage.receiverEmail}, myEmail=$myEmail, isSentByMe=$isSentByMe, isAddressedToMe=$isAddressedToMe)")

            // Skip messages from ourselves if we recently sent the same content
            // This prevents duplicate messages when the server echoes back our sent messages
            if (isFromMe && !myEmail.isNullOrBlank()) {
                val recentMessages = chatDao.getMessagesList(conversationId)
                Log.d(TAG, "Checking ${recentMessages.size} recent messages for duplicates")

                // Log all recent messages for debugging
                recentMessages.takeLast(5).forEach { msg ->
                    Log.d(TAG, "Recent msg: senderId=${msg.senderId}, content=${msg.content}, isFromMe=${msg.isFromMe}, timestamp=${msg.timestamp}")
                }

                // Only check messages from the last 2 seconds (reduced from 5 for more precision)
                val isDuplicate = recentMessages.any { existing ->
                    // Check if the existing message was sent by the same sender (not just isFromMe flag)
                    val sameAuthor = existing.senderId.equals(wsMessage.senderEmail, ignoreCase = true)
                    val sameContent = existing.content == wsMessage.content
                    val timeDiff = Math.abs(existing.timestamp - timestamp)
                    val recentTime = timeDiff < 2000 // Reduced to 2 seconds from 5 seconds

                    if (sameAuthor && sameContent && recentTime) {
                        Log.d(TAG, "Found potential duplicate: existing.senderId=${existing.senderId}, existing.content=${existing.content}, timeDiff=${timeDiff}ms")
                    }

                    sameAuthor && sameContent && recentTime
                }

                if (isDuplicate) {
                    Log.d(TAG, "⚠️ SKIPPING duplicate echo: sender=${wsMessage.senderEmail}, content=${wsMessage.content}, myEmail=$myEmail")
                    return
                } else {
                    Log.d(TAG, "✅ NOT duplicate, processing: ${wsMessage.content}")
                }
            } else {
                Log.d(TAG, "✅ Message from peer (${wsMessage.senderEmail}), processing: ${wsMessage.content}")
            }

            val peerEmail = if (isFromMe) wsMessage.receiverEmail else wsMessage.senderEmail

            // Create message
            val message = ChatMessage(
                id = wsMessage.id ?: UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderId = wsMessage.senderEmail,
                senderName = wsMessage.senderEmail, // Use email as name for now
                senderAvatar = null,
                recipientId = wsMessage.receiverEmail,
                content = wsMessage.content,
                timestamp = timestamp,
                status = MessageStatus.DELIVERED,
                isFromMe = isFromMe
            )

            Log.d(TAG, "💾 Saving message to DB: id=${message.id}, isFromMe=${message.isFromMe}, sender=${message.senderId}, recipient=${message.recipientId}, content=${message.content}")

            chatDao.insertMessage(message)

            Log.d(TAG, "✅ Message saved to database successfully: ${message.id}")

            // Update conversation
            val conversation = chatDao.getConversation(conversationId)
            if (conversation != null) {
                chatDao.updateConversation(conversation.copy(
                    lastMessage = message.content,
                    lastMessageTime = message.timestamp,
                    unreadCount = if (isFromMe) conversation.unreadCount else conversation.unreadCount + 1
                ))
                Log.d(TAG, "Updated existing conversation: $conversationId")
            } else {
                // Create new conversation for incoming message
                val newConversation = Conversation(
                    id = conversationId,
                    peerEmail = peerEmail,
                    peerName = peerEmail, // Use email as name for now
                    peerAvatar = null,
                    lastMessage = message.content,
                    lastMessageTime = message.timestamp,
                    unreadCount = if (isFromMe) 0 else 1
                )
                chatDao.insertConversation(newConversation)
                Log.d(TAG, "Created new conversation: $conversationId with peer: $peerEmail")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle incoming message", e)
        }
    }

    private fun generateConversationId(email1: String?, email2: String?): String {
        // Generate deterministic conversation ID by sorting emails
        val e1 = email1?.trim() ?: ""
        val e2 = email2?.trim() ?: ""

        if (e1.isEmpty() || e2.isEmpty()) {
            Log.w(TAG, "generateConversationId called with empty email(s): '$e1', '$e2'")
            return "unknown_${System.currentTimeMillis()}"
        }

        val sorted = listOf(e1, e2).sorted()
        return "${sorted[0]}_${sorted[1]}".replace("@", "_").replace(".", "_")
    }
}
