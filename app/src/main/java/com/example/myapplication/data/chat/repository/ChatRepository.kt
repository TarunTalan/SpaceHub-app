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
import com.google.gson.JsonElement

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

        // Listen to history frames (bulk restore)
        scope.launch {
            webSocketService.history.collect { hist: com.example.myapplication.data.chat.websocket.DirectChatHistory ->
                try {
                    // call restore implementation
                    val msgs = hist.messages ?: emptyList()
                    restoreConversationFromHistory(hist.chatWith, msgs)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore history for ${'$'}{hist.chatWith}: ${'$'}{e.message}")
                }
            }
        }

        // Listen to chat summaries (rooms)
        scope.launch {
            webSocketService.summaries.collect { summary ->
                // For now, just log. UI can observe via repository if needed.
                Log.d(TAG, "Received chat summary with ${'$'}{summary.rooms.size} rooms")
            }
        }
    }

    val connectionState = webSocketService.connectionState

    /** Connect to direct chat websocket for a specific sender/receiver pair. */
    fun connectWebSocket(senderEmail: String, receiverEmail: String) {
        webSocketService.connect(senderEmail, receiverEmail)
    }

    @Suppress("unused")
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

    private fun extractMessageId(elem: JsonElement?): String? {
        return try {
            if (elem == null || elem.isJsonNull) return null
            when {
                elem.isJsonPrimitive && elem.asJsonPrimitive.isNumber -> elem.asLong.toString()
                elem.isJsonPrimitive && elem.asJsonPrimitive.isString -> elem.asString
                else -> elem.toString()
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun handleIncomingMessage(wsMessage: com.example.myapplication.data.chat.websocket.DirectChatMessage) {
        try {
            val senderEmailRaw = wsMessage.senderEmail?.trim().orEmpty()
            val receiverEmailRaw = wsMessage.receiverEmail?.trim().orEmpty()
            val conversationId = generateConversationId(senderEmailRaw, receiverEmailRaw)

            // Only treat delivered/read as control frames that update status. Regular messages are processed below.
            val ctrl = wsMessage.type?.lowercase()?.trim()
            when (ctrl) {
                "delivered", "read" -> {
                    val ackId = wsMessage.messageIdElement?.let { extractMessageId(it) } ?: wsMessage.id?.takeIf { it.isNotBlank() }
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
                "delete", "deleted" -> {
                    // Incoming delete frame: support single-id (messageId/id) and bulk messageIds
                    val idsToProcess = mutableListOf<String>()

                    // collect from messageIds array if present
                    wsMessage.messageIds?.let { arr ->
                        for (mid in arr) {
                            if (!mid.isNullOrBlank()) idsToProcess.add(mid.trim())
                        }
                    }

                    // Accept string/id fields: id property (server may send 'id') or messageIdElement (number/string) -> extract
                    wsMessage.id?.let { if (it.isNotBlank()) idsToProcess.add(it.trim()) }
                    // also accept messageIdElement (JsonElement) -> extract a usable id
                    wsMessage.messageIdElement?.let { e -> extractMessageId(e)?.let { idsToProcess.add(it) } }

                    // Some servers include deletedBy field; if absent, try to infer from sender/receiver fields
                    val deletedByFallback = (wsMessage.senderEmail ?: wsMessage.receiverEmail ?: "").trim()

                    if (idsToProcess.isNotEmpty()) {
                        for (deletedId in idsToProcess.distinct()) {
                            try {
                                val msg = chatDao.getMessageById(deletedId)
                                if (msg != null) {
                                    val deletedBy = wsMessage.deletedBy?.trim().orEmpty().ifEmpty { deletedByFallback }
                                    val myEmail = userDataManager.getEmail() ?: ""
                                    Log.d(TAG, "Processing incoming DELETE id=$deletedId deletedBy=$deletedBy myEmail=$myEmail")

                                    // Per request: for received DELETE payload show fixed text "Deleted"
                                    val deletedText = "Deleted"

                                    val senderDeletedFlag = deletedBy.equals(msg.senderId, ignoreCase = true) || (deletedBy.equals(myEmail, ignoreCase = true) && msg.senderId.equals(myEmail, ignoreCase = true))
                                    val receiverDeletedFlag = !senderDeletedFlag

                                    // Use DAO update to ensure Room emits change and observers receive update immediately
                                    val updated = msg.copy(
                                        senderDeleted = senderDeletedFlag,
                                        receiverDeleted = receiverDeletedFlag,
                                        content = deletedText
                                    )
                                    chatDao.updateMessage(updated)

                                    // If this message was the conversation's lastMessage, recompute lastMessage
                                    val conv = chatDao.getConversation(msg.conversationId)
                                    if (conv != null && conv.lastMessage == msg.content) {
                                        try {
                                            val remaining = chatDao.getMessagesList(msg.conversationId).filter { !(it.senderDeleted && it.receiverDeleted) }
                                            val last = remaining.maxByOrNull { it.timestamp }
                                            if (last != null) {
                                                chatDao.updateConversation(conv.copy(lastMessage = last.content, lastMessageTime = last.timestamp))
                                            } else {
                                                chatDao.updateConversation(conv.copy(lastMessage = null, lastMessageTime = System.currentTimeMillis()))
                                            }
                                        } catch (inner: Exception) {
                                            Log.w(TAG, "Failed to recompute lastMessage after delete: ${'$'}{inner.message}")
                                        }
                                    }
                                } else {
                                    Log.w(TAG, "Incoming DELETE for unknown message id=$deletedId — skipping DB update")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to process incoming delete for id=$deletedId: ${'$'}{e.message}")
                            }
                        }
                    }

                    // Nothing further to do for delete frames
                    return
                }
                else -> {
                    // not a control frame; continue processing below
                }
            }

            val myEmail = userDataManager.getEmail()

            // Defensive null/blank checks (incoming fields may be null when deserialized)
            if (senderEmailRaw.isBlank() && receiverEmailRaw.isBlank()) return

            // Parse timestamp: try ISO Instant, then numeric epoch millis, otherwise fallback to now
            val timestampStr = wsMessage.timestamp?.trim().orEmpty()
            val timestamp = try {
                Instant.parse(timestampStr).toEpochMilli()
            } catch (_: Exception) {
                try {
                    timestampStr.toLong()
                } catch (_: Exception) {
                    System.currentTimeMillis()
                }
            }

            val isSentByMe = !myEmail.isNullOrBlank() && senderEmailRaw.equals(myEmail, ignoreCase = true)
            val isAddressedToMe = !myEmail.isNullOrBlank() && receiverEmailRaw.equals(myEmail, ignoreCase = true)
            val isFromMe = if (isAddressedToMe) false else isSentByMe

            // If this is an echo of a sent message, attempt to update the local record
            if (isFromMe && !myEmail.isNullOrBlank()) {
                val recentMessages = chatDao.getMessagesList(conversationId)
                val matched = recentMessages.firstOrNull { existing ->
                    existing.senderId.equals(senderEmailRaw, ignoreCase = true) &&
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
                        Log.w(TAG, "Failed to update status for echoed message ${'$'}{matched.id}", e)
                    }
                }
            }

            val peerEmail = if (isFromMe) receiverEmailRaw else senderEmailRaw

            val messageContent = wsMessage.content?.trim().orEmpty()

            // Create and persist incoming message
            val message = ChatMessage(
                id = wsMessage.id ?: wsMessage.messageIdElement?.let { extractMessageId(it) } ?: UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderId = senderEmailRaw,
                // prefer senderUsername if server provides it
                senderName = (wsMessage.senderUsername?.takeIf { it.isNotBlank() } ?: wsMessage.senderEmail?.trim().orEmpty()),
                senderAvatar = null,
                recipientId = receiverEmailRaw,
                content = messageContent,
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
                    // Show peer username when available, otherwise email
                    peerName = (wsMessage.senderUsername?.takeIf { it.isNotBlank() } ?: peerEmail),
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

        if (e1.isEmpty() || e2.isEmpty()) return "unknown_${'$'}{System.currentTimeMillis()}"

        val sorted = listOf(e1, e2).sorted()
        return "${'$'}{sorted[0]}_${'$'}{sorted[1]}".replace("@", "_").replace(".", "_")
    }

    /**
     * Persist a list of DirectChatMessage objects received from server/websocket as conversation history.
     * The parameter chatWith is the peer email for which the history belongs.
     */
    suspend fun restoreConversationFromHistory(chatWith: String, messages: List<com.example.myapplication.data.chat.websocket.DirectChatMessage>): Result<Unit> {
        return try {
            val myEmail = userDataManager.getEmail() ?: return Result.failure(IllegalStateException("No local user email set"))
            val conversationId = generateConversationId(myEmail, chatWith)

            var latestTs = 0L

            messages.forEach { ws ->
                try {
                    // parse timestamp (ISO or epoch millis)
                    val ts = try {
                        Instant.parse(ws.timestamp?.trim().orEmpty()).toEpochMilli()
                    } catch (_: Exception) {
                        try { ws.timestamp?.trim()?.toLong() ?: System.currentTimeMillis() } catch (_: Exception) { System.currentTimeMillis() }
                    }

                    latestTs = maxOf(latestTs, ts)

                    val isFromMe = myEmail.isNotBlank() && ws.senderEmail?.equals(myEmail, ignoreCase = true) == true

                    val msg = ChatMessage(
                        id = ws.id ?: ws.messageIdElement?.let { extractMessageId(it) } ?: UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        senderId = ws.senderEmail?.trim().orEmpty(),
                        senderName = (ws.senderUsername?.takeIf { it.isNotBlank() } ?: ws.senderEmail?.trim().orEmpty()),
                        senderAvatar = null,
                        recipientId = ws.receiverEmail?.trim().orEmpty(),
                        content = ws.content?.trim().orEmpty(),
                        timestamp = ts,
                        status = MessageStatus.DELIVERED,
                        isFromMe = isFromMe
                    )

                    // Insert message (DAO should handle conflicts appropriately)
                    chatDao.insertMessage(msg)
                } catch (inner: Exception) {
                    Log.w(TAG, "Failed to persist history message: ${'$'}{inner.message}")
                }
            }

            // Update or insert conversation record
            val conv = chatDao.getConversation(conversationId)
            if (conv != null) {
                chatDao.updateConversation(conv.copy(lastMessage = messages.lastOrNull()?.content, lastMessageTime = if (latestTs>0) latestTs else System.currentTimeMillis()))
            } else {
                chatDao.insertConversation(Conversation(
                    id = conversationId,
                    peerEmail = chatWith,
                    peerName = chatWith,
                    peerAvatar = null,
                    lastMessage = messages.lastOrNull()?.content,
                    lastMessageTime = if (latestTs>0) latestTs else System.currentTimeMillis(),
                    unreadCount = 0
                ))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "restoreConversationFromHistory failed", e)
            Result.failure(e)
        }
    }

    /**
     * Delete messages by id: send delete frame to server and mark locally as deleted for sender (if they own it)
     */
    suspend fun deleteMessages(messageIds: List<String>): Result<Unit> {
        if (messageIds.isEmpty()) return Result.success(Unit)

        try {
            // Send delete request over websocket (non-blocking boolean)
            try {
                Log.d(TAG, "Sending WS DELETE for ids=${'$'}{messageIds}")
                // include conversationWith and deletedBy (my email) when available
                val myEmail = userDataManager.getEmail() ?: ""
                val peer = if (messageIds.size == 1) {
                    // attempt to lookup conversation from DB for single id
                    try { chatDao.getMessageById(messageIds.first())?.recipientId ?: "" } catch (_: Exception) { "" }
                } else ""
                webSocketService.sendDelete(messageIds, peer, myEmail)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send delete frame: ${'$'}{e.message}")
            }

            // Perform DB updates on repository scope because DAO methods are suspend functions
            scope.launch {
                val myEmail = userDataManager.getEmail() ?: ""
                for (id in messageIds) {
                    try {
                        val msg = chatDao.getMessageById(id)
                        if (msg != null) {
                            val deletedText = if (myEmail.isNotBlank()) "deleted by ${'$'}myEmail" else "message deleted"
                            val senderDeletedFlag = msg.senderId.equals(myEmail, ignoreCase = true)
                            val receiverDeletedFlag = !senderDeletedFlag

                            // Use DAO update to ensure observers are notified immediately
                            val updated = msg.copy(
                                senderDeleted = senderDeletedFlag,
                                receiverDeleted = receiverDeletedFlag,
                                content = deletedText
                            )
                            chatDao.updateMessage(updated)

                            // If the deleted message was the conversation's lastMessage, recompute
                            try {
                                val conv = chatDao.getConversation(msg.conversationId)
                                if (conv != null && conv.lastMessage == msg.content) {
                                    val remaining = chatDao.getMessagesList(msg.conversationId).filter { !(it.senderDeleted && it.receiverDeleted) }
                                    val last = remaining.maxByOrNull { it.timestamp }
                                    if (last != null) {
                                        chatDao.updateConversation(conv.copy(lastMessage = last.content, lastMessageTime = last.timestamp))
                                    } else {
                                        chatDao.updateConversation(conv.copy(lastMessage = null, lastMessageTime = System.currentTimeMillis()))
                                    }
                                }
                            } catch (inner: Exception) {
                                Log.w(TAG, "Failed to recompute conversation after delete: ${'$'}{inner.message}")
                            }
                        }
                    } catch (inner: Exception) {
                        Log.w(TAG, "Failed to update deletion flag for message ${'$'}id: ${'$'}{inner.message}")
                    }
                }
            }

            return Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "deleteMessages failed", e)
            return Result.failure(e)
        }
    }

}
