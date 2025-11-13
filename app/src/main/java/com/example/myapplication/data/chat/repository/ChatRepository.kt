package com.example.myapplication.data.chat.repository

import android.content.Context
import android.util.Log
import com.example.myapplication.data.chat.db.ChatDao
import com.example.myapplication.data.chat.model.ChatMessage
import com.example.myapplication.data.chat.model.Conversation
import com.example.myapplication.data.chat.model.MessageStatus
import com.example.myapplication.data.chat.websocket.DirectChatWebSocketService
import com.example.myapplication.data.user.UserDataManager
import com.google.gson.JsonElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import kotlin.collections.ArrayDeque
import kotlin.collections.LinkedHashSet
import kotlin.collections.List
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.distinct
import kotlin.collections.emptyList
import kotlin.collections.filter
import kotlin.collections.first
import kotlin.collections.firstOrNull
import kotlin.collections.forEach
import kotlin.collections.getOrNull
import kotlin.collections.isNotEmpty
import kotlin.collections.iterator
import kotlin.collections.lastOrNull
import kotlin.collections.listOf
import kotlin.collections.maxByOrNull
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.set
import kotlin.collections.sorted

class ChatRepository private constructor(
    context: Context,
    private val chatDao: ChatDao
) {

    // In-memory dedupe cache to prevent duplicate processing of the same incoming WS frame.
    private val recentIncomingKeys = LinkedHashSet<String>()
    private val recentIncomingQueue = ArrayDeque<String>()
    private val RECENT_INCOMING_SIZE = 500

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

    // Emit chatWith strings when a history payload for that peer has been persisted
    private val _historyProcessed = MutableSharedFlow<String>(replay = 1)
    val historyProcessed: SharedFlow<String> = _historyProcessed

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

    /**
     * Non-suspending helper: load a history payload (from WS or API) when entering a chat.
     * Call this from UI code when you receive the server history payload for a chat partner.
     */
    fun loadHistoryFromPayload(
        chatWith: String,
        messages: List<com.example.myapplication.data.chat.websocket.DirectChatMessage>
    ) {
        scope.launch {
            try {
                restoreConversationFromHistory(chatWith, messages)
            } catch (e: Exception) {
                Log.w(TAG, "loadHistoryFromPayload failed: ${'$'}{e.message}")
            }
        }
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
            chatDao.insertConversation(
                conversation.copy(
                    lastMessage = content,
                    lastMessageTime = timestamp
                )
            )

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
        // Compute a compact dedupe key and skip if we've already processed a matching incoming frame recently.
        try {
            val serverIdKey = wsMessage.id?.takeIf { it.isNotBlank() }
            val clientEchoKey = wsMessage.messageIdElement?.let { extractMessageId(it) }
            val sigKey = run {
                fun normalize(s: String?) = s?.trim()?.replace(Regex("\\s+"), " ")?.lowercase() ?: ""
                val sender = wsMessage.senderEmail?.trim().orEmpty()
                val receiver = wsMessage.receiverEmail?.trim().orEmpty()
                val content = normalize(wsMessage.content)
                val tsBucket = try {
                    (wsMessage.timestamp?.trim()?.toLong() ?: System.currentTimeMillis()) / 2000L
                } catch (_: Exception) {
                    System.currentTimeMillis() / 2000L
                }
                "sig:${sender}:${receiver}:${content}:$tsBucket"
            }

            val key = when {
                !serverIdKey.isNullOrBlank() -> "id:$serverIdKey"
                !clientEchoKey.isNullOrBlank() -> "mid:$clientEchoKey"
                else -> sigKey
            }

            var skip = false
            synchronized(recentIncomingKeys) {
                if (recentIncomingKeys.contains(key)) skip = true else {
                    recentIncomingQueue.addLast(key)
                    recentIncomingKeys.add(key)
                    if (recentIncomingQueue.size > RECENT_INCOMING_SIZE) {
                        val old = recentIncomingQueue.removeFirst()
                        recentIncomingKeys.remove(old)
                    }
                }
            }
            if (skip) {
                Log.d(TAG, "REPO_DEDUPE_SKIP: Duplicate incoming frame skipped: $key")
                return
            }
        } catch (_: Exception) {
            // continue if dedupe check fails for any reason
        }
        try {
            val senderEmailRaw = wsMessage.senderEmail?.trim().orEmpty()
            val receiverEmailRaw = wsMessage.receiverEmail?.trim().orEmpty()
            val conversationId = generateConversationId(senderEmailRaw, receiverEmailRaw)

            // Only treat delivered/read as control frames that update status. Regular messages are processed below.
            val ctrl = wsMessage.type?.lowercase()?.trim()
            when (ctrl) {
                "delivered", "read" -> {
                    val ackId = wsMessage.messageIdElement?.let { extractMessageId(it) }
                        ?: wsMessage.id?.takeIf { it.isNotBlank() }
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
                                    Log.d(
                                        TAG,
                                        "Processing incoming DELETE id=$deletedId deletedBy=$deletedBy myEmail=$myEmail"
                                    )

                                    // Per request: for received DELETE payload show fixed text "Deleted"
                                    val deletedText = "Deleted"

                                    val senderDeletedFlag =
                                        deletedBy.equals(msg.senderId, ignoreCase = true) || (deletedBy.equals(
                                            myEmail,
                                            ignoreCase = true
                                        ) && msg.senderId.equals(myEmail, ignoreCase = true))
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
                                            val remaining = chatDao.getMessagesList(msg.conversationId)
                                                .filter { !(it.senderDeleted && it.receiverDeleted) }
                                            val last = remaining.maxByOrNull { it.timestamp }
                                            if (last != null) {
                                                chatDao.updateConversation(
                                                    conv.copy(
                                                        lastMessage = last.content,
                                                        lastMessageTime = last.timestamp
                                                    )
                                                )
                                            } else {
                                                chatDao.updateConversation(
                                                    conv.copy(
                                                        lastMessage = null,
                                                        lastMessageTime = System.currentTimeMillis()
                                                    )
                                                )
                                            }
                                        } catch (inner: Exception) {
                                            Log.w(
                                                TAG,
                                                "Failed to recompute lastMessage after delete: ${'$'}{inner.message}"
                                            )
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

            // Fallback fuzzy matching if server didn't echo client messageId.
            // Use normalized content comparison and a larger time window (10s) to tolerate network delays
            if (isFromMe && !myEmail.isNullOrBlank()) {
                fun normalize(s: String?): String = s?.trim()?.replace(Regex("\\s+"), " ")?.lowercase() ?: ""
                val incomingNormalized = normalize(wsMessage.content)
                val recentMessages = chatDao.getMessagesList(conversationId)
                val timeWindowMs = 10_000L
                val matched = recentMessages.firstOrNull { existing ->
                    // Match if normalized content equals and timestamp is close (allow different sender to catch replays)
                    normalize(existing.content) == incomingNormalized &&
                            Math.abs(existing.timestamp - timestamp) < timeWindowMs
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
            val incomingId =
                wsMessage.id ?: wsMessage.messageIdElement?.let { extractMessageId(it) } ?: UUID.randomUUID().toString()

            // Dedupe: try deterministic lookups: by client-echoed id (messageIdElement) and serverId (wsMessage.id)
            val clientEchoedId = wsMessage.messageIdElement?.let { extractMessageId(it) }
            val existingByClientId = try {
                clientEchoedId?.let { chatDao.getMessageById(it) }
            } catch (_: Exception) {
                null
            }
            val existingByServerId = try {
                wsMessage.id?.takeIf { it.isNotBlank() }?.let { chatDao.getMessageByServerId(it) }
            } catch (_: Exception) {
                null
            }
            val existingById = existingByClientId ?: existingByServerId
            val serverAssignedId = wsMessage.id?.takeIf { it.isNotBlank() }

            if (existingById != null) {
                // update existing record with newer fields
                val updated = existingById.copy(
                    content = messageContent.ifBlank { existingById.content },
                    timestamp = timestamp.takeIf { it > 0 } ?: existingById.timestamp,
                    status = MessageStatus.DELIVERED,
                    serverId = existingById.serverId ?: serverAssignedId
                )
                try {
                    chatDao.updateMessage(updated)
                } catch (_: Exception) {
                }
            } else {
                // Fuzzy dedupe: match by normalized content + sender + small time window
                fun normalize(s: String?) = s?.trim()?.replace(Regex("\\s+"), " ")?.lowercase() ?: ""
                val incomingNormalized = normalize(messageContent)
                val recent = try { chatDao.getMessagesList(conversationId) } catch (_: Exception) { emptyList() }
                val timeWindowMs = 2000L
                val fuzzyMatch = recent.firstOrNull { existing ->
                    // Match if normalized content equals and timestamp is close (allow different sender to catch replays)
                    normalize(existing.content) == incomingNormalized &&
                            Math.abs(existing.timestamp - timestamp) < timeWindowMs
                }

                if (fuzzyMatch != null) {
                    try {
                        val updated = fuzzyMatch.copy(
                            content = messageContent.ifBlank { fuzzyMatch.content },
                            timestamp = timestamp.takeIf { it > 0 } ?: fuzzyMatch.timestamp,
                            status = MessageStatus.DELIVERED
                        )
                        chatDao.updateMessage(updated)
                    } catch (_: Exception) {
                        // fallback: insert if update fails
                        try {
                            val msg = ChatMessage(
                                id = incomingId,
                                conversationId = conversationId,
                                senderId = senderEmailRaw,
                                senderName = (wsMessage.senderUsername?.takeIf { it.isNotBlank() }
                                    ?: wsMessage.senderEmail?.trim().orEmpty()),
                                senderAvatar = null,
                                recipientId = receiverEmailRaw,
                                content = messageContent,
                                timestamp = timestamp,
                                status = MessageStatus.DELIVERED,
                                isFromMe = isFromMe,
                                serverId = serverAssignedId
                            )
                            saveOrUpdateMessage(msg)
                        } catch (_: Exception) {
                        }
                    }
                } else {
                    // No match -> insert new message
                    try {
                        val msg = ChatMessage(
                            id = incomingId,
                            conversationId = conversationId,
                            senderId = senderEmailRaw,
                            senderName = (wsMessage.senderUsername?.takeIf { it.isNotBlank() }
                                ?: wsMessage.senderEmail?.trim().orEmpty()),
                            senderAvatar = null,
                            recipientId = receiverEmailRaw,
                            content = messageContent,
                            timestamp = timestamp,
                            status = MessageStatus.DELIVERED,
                            isFromMe = isFromMe,
                            serverId = serverAssignedId
                        )
                        saveOrUpdateMessage(msg)
                    } catch (_: Exception) {
                    }
                }
            }

            // Update conversation metadata using the incoming message content/timestamp
            val finalContent = messageContent
            val finalTs = timestamp
            val conversation = chatDao.getConversation(conversationId)
            if (conversation != null) {
                chatDao.updateConversation(
                    conversation.copy(
                        lastMessage = finalContent,
                        lastMessageTime = finalTs,
                        unreadCount = if (isFromMe) conversation.unreadCount else conversation.unreadCount + 1
                    )
                )
            } else {
                chatDao.insertConversation(
                    Conversation(
                        id = conversationId,
                    peerEmail = peerEmail,
                    // Show peer username when available, otherwise email
                    peerName = (wsMessage.senderUsername?.takeIf { it.isNotBlank() } ?: peerEmail),
                    peerAvatar = null,
                    lastMessage = finalContent,
                    lastMessageTime = finalTs,
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
    suspend fun restoreConversationFromHistory(
        chatWith: String,
        messages: List<com.example.myapplication.data.chat.websocket.DirectChatMessage>
    ): Result<Unit> {
        return try {
            // Do not abort if local email is not yet available; persist history anyway.
            val myEmail = userDataManager.getEmail() ?: ""

            // Track per-conversation metadata (lastMessage, lastTs) because server history may include multiple peers
            val convLatest = mutableMapOf<String, Long>()
            val convLastMessage = mutableMapOf<String, String?>()

            messages.forEach { ws ->
                try {
                    // parse timestamp (ISO or epoch millis)
                    val ts = try {
                        Instant.parse(ws.timestamp?.trim().orEmpty()).toEpochMilli()
                    } catch (_: Exception) {
                        try {
                            ws.timestamp?.trim()?.toLong() ?: System.currentTimeMillis()
                        } catch (_: Exception) {
                            System.currentTimeMillis()
                        }
                    }

                    // Use the raw sender/receiver values if present (do not auto-fallback to chatWith yet)
                    val providedSender = ws.senderEmail?.trim().takeUnless { it.isNullOrBlank() }
                    val providedReceiver = ws.receiverEmail?.trim().takeUnless { it.isNullOrBlank() }

                    // Skip ambiguous history items: if both sender and receiver are missing or
                    // if neither participant equals the local user, we can't reliably assign the message.
                    if (providedSender.isNullOrBlank() && providedReceiver.isNullOrBlank()) {
                        // Nothing to assign this history item to
                        Log.d(TAG, "SKIP_HISTORY: ambiguous item with no sender/receiver; skipping")
                        return@forEach
                    }
                    // If we have the local user's email, skip items that are unrelated to this user
                    if (myEmail.isNotBlank() && !providedSender.equals(myEmail, ignoreCase = true) && !providedReceiver.equals(
                            myEmail,
                            ignoreCase = true
                        )
                    ) {
                        Log.d(
                            TAG,
                            "SKIP_HISTORY: unrelated history item sender=${providedSender} receiver=${providedReceiver}; skipping"
                        )
                        return@forEach
                    }

                    // Determine sender/receiver for this history item; fall back to chatWith when one side missing
                    val sender = providedSender ?: chatWith
                    val receiver = providedReceiver ?: chatWith

                    // Determine peer (the other participant in 1:1)
                    val peerEmail = when {
                        sender.equals(myEmail, ignoreCase = true) -> receiver
                        receiver.equals(myEmail, ignoreCase = true) -> sender
                        else -> chatWith // unlikely due to above guard, but keep a fallback
                    }

                    val conversationId = generateConversationId(myEmail, peerEmail)

                    // Update per-conversation latest markers
                    convLatest[conversationId] = maxOf(convLatest[conversationId] ?: 0L, ts)
                    convLastMessage[conversationId] = ws.content?.trim().orEmpty()

                    val isFromMe = myEmail.isNotBlank() && sender.equals(myEmail, ignoreCase = true)

                    // Try to reconcile history item with an existing local message first.
                    val echoedId =
                        ws.messageIdElement?.let { extractMessageId(it) } ?: ws.id?.takeIf { it.isNotBlank() }
                    var matchedLocal: ChatMessage? = null
                    if (!echoedId.isNullOrBlank()) {
                        try {
                            matchedLocal = chatDao.getMessageById(echoedId)
                        } catch (_: Exception) {
                            matchedLocal = null
                        }
                    }

                    // Fallback fuzzy match when echoed id not present or didn't match: compare normalized content and time window
                    if (matchedLocal == null && isFromMe) {
                        fun normalize(s: String?) = s?.trim()?.replace(Regex("\\s+"), " ")?.lowercase() ?: ""
                        val incomingNormalized = normalize(ws.content)
                        val timeWindowMs = 60_000L // 60 seconds window for history reconciliation
                        val recent = try {
                            chatDao.getMessagesList(conversationId)
                        } catch (_: Exception) {
                            emptyList()
                        }
                        matchedLocal = recent.firstOrNull { existing ->
                            // Accept match by normalized content and time window; for history reconciliation we prefer same-sender but allow content-match
                            normalize(existing.content) == incomingNormalized &&
                                    Math.abs(existing.timestamp - ts) < timeWindowMs
                        }
                    }

                    if (matchedLocal != null) {
                        // Update the existing local message with server-provided fields where appropriate
                        try {
                            val updated = matchedLocal.copy(
                                content = ws.content?.trim().orEmpty().ifBlank { matchedLocal.content },
                                timestamp = ts.takeIf { it > 0 } ?: matchedLocal.timestamp,
                                status = MessageStatus.DELIVERED
                            )
                            chatDao.updateMessage(updated)
                        } catch (inner: Exception) {
                            Log.w(TAG, "Failed to update matched local message from history: ${'$'}{inner.message}")
                        }
                    } else {
                        // No match found: insert as a new message assigned to this conversationId
                        val msg = ChatMessage(
                            id = ws.id ?: ws.messageIdElement?.let { extractMessageId(it) } ?: UUID.randomUUID()
                                .toString(),
                            conversationId = conversationId,
                            senderId = sender,
                            senderName = (ws.senderUsername?.takeIf { it.isNotBlank() } ?: sender),
                            senderAvatar = null,
                            recipientId = receiver,
                            content = ws.content?.trim().orEmpty(),
                            timestamp = ts,
                            status = MessageStatus.DELIVERED,
                            isFromMe = isFromMe,
                            serverId = ws.id?.takeIf { it.isNotBlank() }
                        )

                        // Insert message using robust helper
                        saveOrUpdateMessage(msg)
                    }
                } catch (inner: Exception) {
                    Log.w(TAG, "Failed to persist history message: ${'$'}{inner.message}")
                }
            }

            // Update or insert conversation records for all affected conversations
            for ((convId, lastTs) in convLatest) {
                try {
                    val lastMsg = convLastMessage[convId]
                    val conv = chatDao.getConversation(convId)
                    if (conv != null) {
                        chatDao.updateConversation(
                            conv.copy(
                                lastMessage = lastMsg,
                                lastMessageTime = if (lastTs > 0) lastTs else System.currentTimeMillis()
                            )
                        )
                    } else {
                        // create placeholder peerEmail/name from convId parts if needed
                        val parts = convId.split("_")
                        val peer = parts.getOrNull(1) ?: chatWith
                        chatDao.insertConversation(
                            Conversation(
                                id = convId,
                                peerEmail = peer,
                                peerName = peer,
                                peerAvatar = null,
                                lastMessage = lastMsg,
                                lastMessageTime = if (lastTs > 0) lastTs else System.currentTimeMillis(),
                                unreadCount = 0
                            )
                        )
                    }
                } catch (inner: Exception) {
                    Log.w(TAG, "Failed to update conversation metadata for conv=$convId: ${'$'}{inner.message}")
                }
            }

            val success = Result.success(Unit)
            // Notify listeners that history for this chatWith was processed
            try { _historyProcessed.emit(chatWith) } catch (_: Exception) { }
            return success
         } catch (e: Exception) {
            Log.e(TAG, "restoreConversationFromHistory failed", e)
            return Result.failure(e)
         }
     }

    /**
     * Delete messages by id: send delete frame to server and mark locally as deleted for sender (if they own it)
     */
    suspend fun deleteMessages(messageIds: List<String>): Result<Unit> {
        if (messageIds.isEmpty()) return Result.success(Unit)

        try {
            // Map local message IDs to server-assigned ids (serverId or id) when available.
            val actorEmail = userDataManager.getEmail() ?: ""
            val mappedIds = mutableListOf<String>()
            for (id in messageIds) {
                try {
                    val msg = chatDao.getMessageById(id)
                    val sid = msg?.serverId ?: msg?.id ?: id
                    mappedIds.add(sid)
                } catch (_: Exception) {
                    // If lookup fails, fall back to the provided id
                    mappedIds.add(id)
                }
            }

            Log.d(TAG, "Sending WS DELETE for serverIds=${'$'}{mappedIds} (originalIds=${'$'}{messageIds})")

            // compute conversationWith for single-id deletes (best-effort)
            val conversationWith = if (messageIds.size == 1) {
                try { chatDao.getMessageById(messageIds.first())?.recipientId ?: "" } catch (_: Exception) { "" }
            } else ""

            // send a single, mapped delete request (websocket service will queue if not connected)
            try {
                webSocketService.sendDelete(mappedIds, conversationWith, actorEmail)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send delete frame: ${'$'}{e.message}")
            }

            // Update local DB to mark messages as deleted (perform async so UI doesn't block)
            scope.launch {
                val myEmail = actorEmail
                for (localId in messageIds) {
                    try {
                        val msg = chatDao.getMessageById(localId)
                        if (msg != null) {
                            val deletedText = if (myEmail.isNotBlank()) "deleted by ${'$'}myEmail" else "message deleted"
                            val senderDeletedFlag = msg.senderId.equals(myEmail, ignoreCase = true)
                            val receiverDeletedFlag = !senderDeletedFlag

                            val updated = msg.copy(
                                senderDeleted = senderDeletedFlag,
                                receiverDeleted = receiverDeletedFlag,
                                content = deletedText
                            )
                            chatDao.updateMessage(updated)

                            // If this message was the conversation's lastMessage, recompute lastMessage
                            val conv = try { chatDao.getConversation(msg.conversationId) } catch (_: Exception) { null }
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
                        }
                    } catch (inner: Exception) {
                        Log.w(TAG, "Failed to update deletion flag for message ${'$'}localId: ${'$'}{inner.message}")
                    }
                }
            }

            return Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "deleteMessages failed", e)
            return Result.failure(e)
        }
    }

    // Robust insert/update helper used by history & incoming message handlers
    private suspend fun saveOrUpdateMessage(msg: ChatMessage) {
        try {
            chatDao.insertMessage(msg)
            Log.d(TAG, "DB: inserted message id=${'$'}{msg.id} serverId=${'$'}{msg.serverId} conv=${'$'}{msg.conversationId}")
            return
        } catch (e: Exception) {
            // Insert failed (possibly unique index on serverId). Try to reconcile by serverId or fuzzy match.
            try {
                val serverId = msg.serverId
                if (!serverId.isNullOrBlank()) {
                    val existing = chatDao.getMessageByServerId(serverId)
                    if (existing != null) {
                        val updated = existing.copy(
                            content = msg.content.ifBlank { existing.content },
                            timestamp = msg.timestamp.takeIf { it > 0 } ?: existing.timestamp,
                            status = msg.status
                        )
                        chatDao.updateMessage(updated)
                        Log.d(TAG, "DB: reconciled by serverId=${'$'}{serverId} updated existing id=${'$'}{existing.id}")
                        return
                    }
                }

                // Fallback: try fuzzy match on conversation
                val recent = try { chatDao.getMessagesList(msg.conversationId) } catch (_: Exception) { emptyList() }
                val norm = msg.content.trim().replace(Regex("\\s+"), " ").lowercase()
                val match = recent.firstOrNull { existing ->
                    existing.senderId == msg.senderId && existing.content.trim().replace(Regex("\\s+"), " ").lowercase() == norm &&
                            Math.abs(existing.timestamp - msg.timestamp) < 2000L
                }
                if (match != null) {
                    val updated = match.copy(
                        content = msg.content.ifBlank { match.content },
                        timestamp = msg.timestamp.takeIf { it > 0 } ?: match.timestamp,
                        status = msg.status,
                        serverId = match.serverId ?: msg.serverId
                    )
                    chatDao.updateMessage(updated)
                    Log.d(TAG, "DB: reconciled by fuzzy match: matched id=${'$'}{match.id} for incoming id=${'$'}{msg.id} serverId=${'$'}{msg.serverId}")
                    return
                }
            } catch (inner: Exception) {
                Log.w(TAG, "saveOrUpdateMessage reconciliation failed: ${'$'}{inner.message}")
            }

            // As a last resort, try insert again (may still throw)
            try {
                chatDao.insertMessage(msg)
                Log.d(TAG, "DB: inserted on retry message id=${'$'}{msg.id} serverId=${'$'}{msg.serverId}")
            } catch (ex: Exception) {
                Log.w(TAG, "DB: final insert failed for id=${'$'}{msg.id} serverId=${'$'}{msg.serverId}: ${'$'}{ex.message}")
            }
        }
    }

    // End of ChatRepository class
}
