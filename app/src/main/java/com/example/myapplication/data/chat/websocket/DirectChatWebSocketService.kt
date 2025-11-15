package com.example.myapplication.data.chat.websocket

import android.content.Context
import android.util.Log
import com.example.myapplication.data.community.database.CommunityDatabase
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import okhttp3.*
import okio.ByteString
import java.lang.ref.WeakReference
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class DirectChatWebSocketService private constructor(private val context: Context) {

    private val gson = Gson()
    private var webSocket: WebSocket? = null

    // Track which sender/receiver pair this webSocket was opened for (null = global)
    private var connectedSender: String? = null
    private var connectedReceiver: String? = null
    private val messageChannel = Channel<DirectChatMessage>(Channel.BUFFERED)
    private val historyChannel = Channel<DirectChatHistory>(Channel.BUFFERED)
    private val summaryChannel = Channel<ChatSummary>(Channel.BUFFERED)
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.DISCONNECTED)

    // In-memory recent-message dedupe cache to avoid forwarding duplicate frames.
    // We store compact string keys and keep a bounded queue for eviction.
    private val recentMessageKeysSet = LinkedHashSet<String>()
    private val recentMessageQueue = ArrayDeque<String>()
    private val RECENT_CACHE_SIZE = 300

    val messages: Flow<DirectChatMessage> = messageChannel.receiveAsFlow()
    val history: Flow<DirectChatHistory> = historyChannel.receiveAsFlow()
    val summaries: Flow<ChatSummary> = summaryChannel.receiveAsFlow()
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // Outgoing queue when socket is not connected
    private val outgoingQueue = mutableListOf<DirectChatMessageRequest>()
    private val outgoingLock = Any()

    // Support queued delete requests
    private val outgoingDeleteQueue = mutableListOf<DeleteMessageRequest>()

    private data class DeleteMessageRequest(
        val messageIds: List<String>,
        val conversationWith: String,
        val deletedBy: String?
    )

    // Track pending deletes we attempted to send: map serverUuid -> local serverIds list
    private val pendingDeleteMap = mutableMapOf<String, List<String>>()

    // Background scope for DB operations in this service
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Optional auth token - can be set by caller so the service will add Authorization header
    private var authToken: String? = null

    fun setAuthToken(token: String?) {
        authToken = token
    }

    companion object {
        // Use a WeakReference to avoid holding a strong static reference to a Context-holding object
        @Volatile
        private var INSTANCE_REF: WeakReference<DirectChatWebSocketService>? = null

        fun getInstance(context: Context): DirectChatWebSocketService {
            val existing = INSTANCE_REF?.get()
            if (existing != null) return existing

            return synchronized(this) {
                val again = INSTANCE_REF?.get()
                if (again != null) return@synchronized again
                val inst = DirectChatWebSocketService(context.applicationContext)
                INSTANCE_REF = WeakReference(inst)
                inst
            }
        }

        private const val TAG = "DirectChatWebSocket"
        private const val WS_URL = "wss://codewithketan.me/ws/direct-chat"
    }

    /**
     * Connect to the direct-chat WebSocket.
     * If senderEmail and receiverEmail are provided, append them as query parameters per server API.
     */
    fun connect(senderEmail: String? = null, receiverEmail: String? = null) {
        // If already connected for the same pair, no-op
        if (webSocket != null) {
            val same = (connectedSender == senderEmail) && (connectedReceiver == receiverEmail)
            if (same) {
                Log.d(TAG, "WebSocket already connected for same pair; skipping connect")
                return
            }
            // Otherwise close existing and reconnect for new pair
            try {
                webSocket?.close(1000, "Reconnecting for new pair")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close existing websocket before reconnect: ${e.message}")
            }
            webSocket = null
            connectedSender = null
            connectedReceiver = null
        }

        // Build URL with optional query params (senderEmail, receiverEmail)
        val urlBuilder = StringBuilder(WS_URL)
        val params = mutableListOf<String>()
        senderEmail?.takeIf { it.isNotBlank() }?.let { params.add("senderEmail=" + URLEncoder.encode(it, "UTF-8")) }
        receiverEmail?.takeIf { it.isNotBlank() }?.let { params.add("receiverEmail=" + URLEncoder.encode(it, "UTF-8")) }
        if (params.isNotEmpty()) {
            urlBuilder.append("?").append(params.joinToString("&"))
        }

        val finalUrl = urlBuilder.toString()
        Log.d(TAG, "Connecting to WebSocket: $finalUrl")

        _connectionState.value = ConnectionState.CONNECTING

        val requestBuilder = Request.Builder()
            .url(finalUrl)
            // support servers that accept STOMP protocols header as before
            .addHeader("Sec-WebSocket-Protocol", "v10.stomp")

        // add Authorization header if token is present
        authToken?.takeIf { it.isNotBlank() }?.let {
            requestBuilder.addHeader("Authorization", "Bearer $it")
            Log.d(TAG, "Added Authorization header to WebSocket request (masked)")
        }

        val request = requestBuilder.build()

        // remember desired pair so we can detect re-connects
        connectedSender = senderEmail
        connectedReceiver = receiverEmail

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Direct WS open - sending optional STOMP CONNECT (to support servers using STOMP)")
                _connectionState.value = ConnectionState.CONNECTED

                // flush any queued outgoing messages
                synchronized(outgoingLock) {
                    if (outgoingQueue.isNotEmpty()) {
                        Log.d(TAG, "Flushing ${outgoingQueue.size} queued direct messages")
                        val copy = ArrayList(outgoingQueue)
                        outgoingQueue.clear()
                        for (req in copy) {
                            try {
                                // Send minimal payload server expects (type+content); omit messageId so server assigns its own id
                                // Include the client-side messageId so server can map optimistic messages back to client
                                val outgoing = OutgoingSimpleMessage(
                                    type = "MESSAGE",
                                    content = req.content,
                                    messageId = req.messageId
                                )
                                val json = gson.toJson(outgoing)
                                val sent = webSocket.send(json)
                                Log.d(TAG, "Flushed queued message (messageId=${req.messageId}) sent=$sent json=$json")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send queued message: ${e.message}")
                            }
                        }
                    }
                    // flush delete requests too
                    if (outgoingDeleteQueue.isNotEmpty()) {
                        Log.d(TAG, "Flushing ${outgoingDeleteQueue.size} queued delete requests")
                        val copy = ArrayList(outgoingDeleteQueue)
                        outgoingDeleteQueue.clear()
                        for (del in copy) {
                            try {
                                // Server expects messageUuid when deleting a single message. Include both for compatibility.
                                val singleUuid = del.messageIds.firstOrNull()
                                val base = mutableMapOf<String, Any>(
                                    "type" to "DELETE",
                                    "messageIds" to del.messageIds,
                                    "conversationWith" to del.conversationWith
                                )
                                if (!del.deletedBy.isNullOrBlank()) base["deletedBy"] = del.deletedBy
                                if (!singleUuid.isNullOrBlank()) base["messageUuid"] = singleUuid
                                val json = gson.toJson(base)
                                // record pending delete mapping so system responses can be reconciled
                                if (!singleUuid.isNullOrBlank()) synchronized(pendingDeleteMap) {
                                    pendingDeleteMap[singleUuid] = del.messageIds
                                }
                                val sent = webSocket.send(json)
                                Log.d(TAG, "Flushed queued delete (ids=${del.messageIds}) sent=$sent json=$json")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send queued delete request: ${e.message}")
                            }
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received raw WS text: $text")
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // binary frames are not used in current protocol
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                try {
                    webSocket.close(1000, null)
                } catch (_: Exception) {
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.DISCONNECTED
                this@DirectChatWebSocketService.webSocket = null
                connectedSender = null
                connectedReceiver = null
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}", t)
                _connectionState.value = ConnectionState.ERROR(t.message ?: "Connection failed")
                this@DirectChatWebSocketService.webSocket = null
                connectedSender = null
                connectedReceiver = null
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val jsonElement = JsonParser.parseString(text)
            if (jsonElement.isJsonObject) {
                val obj = jsonElement.asJsonObject

                // History payload (server may send 'type':'history' or include chatWith/messages keys)
                val typeElem =
                    if (obj.has("type") && obj.get("type").isJsonPrimitive) obj.get("type").asString.lowercase() else null
                if (typeElem == "history" || (obj.has("chatWith") && obj.has("messages"))) {
                    try {
                        val history = gson.fromJson(text, DirectChatHistory::class.java)

                        // Sanity check: if this websocket connection is tied to a sender/receiver pair,
                        // ensure the history messages belong to that pair before exposing them to consumers.
                        val msgs = history.messages ?: emptyList()
                        var belongsToCurrentPair = true

                        // If we have a connected sender/receiver, validate each message contains both endpoints
                        if (!connectedSender.isNullOrBlank() && !connectedReceiver.isNullOrBlank() && msgs.isNotEmpty()) {
                            for (m in msgs) {
                                val s = m.senderEmail?.trim().orEmpty()
                                val r = m.receiverEmail?.trim().orEmpty()
                                // Accept message if it involves the two endpoints in any order
                                val setMsg = setOf(s.lowercase(), r.lowercase())
                                val setConn = setOf(connectedSender!!.trim().lowercase(), connectedReceiver!!.trim().lowercase())
                                if (setMsg != setConn) {
                                    belongsToCurrentPair = false
                                    break
                                }
                            }
                        }

                        if (msgs.isEmpty() || belongsToCurrentPair) {
                            historyChannel.trySend(history)
                            Log.d(TAG, "Received history for ${history.chatWith} (count=${history.messages?.size ?: 0})")
                        } else {
                            Log.d(TAG, "Skipping history for ${history.chatWith} because it doesn't match connected pair")
                        }

                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse history payload: ${e.message}")
                    }
                }

                // Chat summary / rooms list
                if (typeElem == "chatsummary" || (obj.has("rooms") && obj.has("type") && obj.get("type").asString.lowercase() == "chatsummary")) {
                    try {
                        val summary = gson.fromJson(text, ChatSummary::class.java)
                        summaryChannel.trySend(summary)
                        Log.d(TAG, "Received chat summary payload: ${'$'}text")
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse chatSummary payload: ${'$'}{e.message}")
                    }
                }

                // System message — non-chat informational messages
                if (typeElem == "system") {
                    try {
                        // We'll deliver system notifications as a lightweight DirectChatMessage with type set
                        val systemText =
                            if (obj.has("system") && obj.get("system").isJsonPrimitive) obj.get("system").asString else obj.toString()
                        val sys = DirectChatMessage(
                            id = null,
                            senderEmail = "",
                            receiverEmail = "",
                            content = systemText,
                            timestamp = if (obj.has("timestamp") && obj.get("timestamp").isJsonPrimitive) obj.get("timestamp").asString else "",
                            type = "system",
                            messageIdElement = null,
                            senderUsername = null
                        )
                        messageChannel.trySend(sys)
                        Log.d(TAG, "Received system payload: ${'$'}text")

                        // Special-case: server told us "Message not found or already deleted" for a delete we attempted.
                        if (systemText.contains("Message not found or already deleted", ignoreCase = true)) {
                            bgScope.launch {
                                try {
                                    val chatDao = CommunityDatabase.getInstance(context).chatDao()
                                    val pending = synchronized(pendingDeleteMap) {
                                        val copy = pendingDeleteMap.entries.map { it.key to it.value }
                                        pendingDeleteMap.clear()
                                        copy
                                    }

                                    for ((_, serverIds) in pending) {
                                        for (sid in serverIds) {
                                            try {
                                                val msg = try { chatDao.getMessageByServerId(sid) } catch (_: Exception) { null }
                                                    ?: try { chatDao.getMessageById(sid) } catch (_: Exception) { null }
                                                if (msg != null) {
                                                    val deletedText = "Deleted"
                                                    val updated = msg.copy(senderDeleted = true, receiverDeleted = true, content = deletedText)
                                                    chatDao.updateMessage(updated)
                                                    val deletedMsg = DirectChatMessage(id = msg.serverId ?: msg.id, senderEmail = msg.senderId, receiverEmail = msg.recipientId, content = deletedText, timestamp = System.currentTimeMillis().toString(), type = "deleted", messageIdElement = null, senderUsername = null)
                                                    messageChannel.trySend(deletedMsg)
                                                }
                                            } catch (_: Exception) { /* ignore per-message failures */ }
                                        }
                                    }
                                } catch (_: Exception) { /* ignore */ }
                            }
                        }
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse system payload: ${'$'}{e.message}")
                    }
                }

                // Explicitly handle DELETE frames so they are forwarded reliably to repository
                if (typeElem == "delete" || typeElem == "deleted") {
                    try {
                        val delMsg = gson.fromJson(text, DirectChatMessage::class.java)
                        messageChannel.trySend(delMsg)
                        Log.d(TAG, "Received delete payload forwarded: ${'$'}text")
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse delete payload: ${'$'}{e.message}")
                    }
                }
            }

            // Fallback: try to parse as a single message
            val message = gson.fromJson(text, DirectChatMessage::class.java)
            // If content is blank, attempt to extract from alternate field names that some servers use
            if (message.content.isNullOrBlank()) {
                try {
                    val obj = jsonElement.asJsonObject
                    val altKeys = listOf("message", "text", "body", "msg", "payload")
                    var found: String? = null
                    for (k in altKeys) {
                        if (obj.has(k) && obj.get(k).isJsonPrimitive) {
                            val s = obj.get(k).asString.trim()
                            if (s.isNotEmpty()) { found = s; break }
                        }
                    }
                    if (found != null) {
                        val replaced = message.copy(content = found)
                        Log.d(TAG, "WS_PARSE: replaced empty content with alt key value for messageId=${message.id}")
                        // forward replaced message
                        val normalized = replaced.copy(
                            senderEmail = replaced.senderEmail?.trim().orEmpty(),
                            receiverEmail = replaced.receiverEmail?.trim().orEmpty(),
                            content = replaced.content?.trim().orEmpty(),
                            timestamp = replaced.timestamp?.trim().orEmpty(),
                            type = replaced.type?.trim()?.lowercase() ?: "message"
                        )
                        // Dedup & forward below using same logic (bypass fallthrough)
                        // compute key
                        fun normalizeContent(s: String?) = s?.trim()?.replace(Regex("\\s+"), " ")?.lowercase() ?: ""
                        val key = when {
                            !normalized.id.isNullOrBlank() -> "id:${normalized.id}"
                            normalized.messageIdElement != null -> "mid:${normalized.messageIdElement}"
                            else -> {
                                val tsBucket = try { (normalized.timestamp?.toLong() ?: System.currentTimeMillis()) / 2000L } catch (_: Exception) { System.currentTimeMillis() / 2000L }
                                "sig:${normalized.senderEmail}:${normalized.receiverEmail}:${normalizeContent(normalized.content)}:$tsBucket"
                            }
                        }
                        var isDuplicate = false
                        synchronized(recentMessageKeysSet) {
                            if (recentMessageKeysSet.contains(key)) {
                                isDuplicate = true
                            } else {
                                recentMessageQueue.addLast(key)
                                recentMessageKeysSet.add(key)
                                if (recentMessageQueue.size > RECENT_CACHE_SIZE) {
                                    val old = recentMessageQueue.removeFirst()
                                    recentMessageKeysSet.remove(old)
                                }
                            }
                        }
                        if (!isDuplicate) {
                            Log.d(TAG, "WS_FORWARD (alt-content): forwarding message key=$key type=${normalized.type} from=${normalized.senderEmail} to=${normalized.receiverEmail}")
                            val sent = messageChannel.trySend(normalized)
                            Log.d(TAG, "WS_FORWARD_RESULT (alt-content): key=$key sentToChannel=$sent")
                        } else {
                            Log.d(TAG, "WS_DEDUPE: Duplicate WS message skipped (alt-content): $key")
                        }
                        return
                    } else {
                        Log.d(TAG, "WS_PARSE: message content blank and no alt key found for raw=$text")
                    }
                } catch (e: Exception) { Log.w(TAG, "Alt-content extraction failed: ${e.message}") }
            }

            // Normal flow: dedupe & forward parsed message
            try {
                fun normalizeContent(s: String?) = s?.trim()?.replace(Regex("\\s+"), " ")?.lowercase() ?: ""
                val key = when {
                    !message.id.isNullOrBlank() -> "id:${message.id}"
                    message.messageIdElement != null -> "mid:${message.messageIdElement}"
                    else -> {
                        val tsBucket = try { (message.timestamp?.toLong() ?: System.currentTimeMillis()) / 2000L } catch (_: Exception) { System.currentTimeMillis() / 2000L }
                        "sig:${message.senderEmail}:${message.receiverEmail}:${normalizeContent(message.content)}:$tsBucket"
                    }
                }
                var isDuplicate = false
                synchronized(recentMessageKeysSet) {
                    if (recentMessageKeysSet.contains(key)) isDuplicate = true else {
                        recentMessageQueue.addLast(key)
                        recentMessageKeysSet.add(key)
                        if (recentMessageQueue.size > RECENT_CACHE_SIZE) recentMessageQueue.removeFirst()
                    }
                }
                if (isDuplicate) {
                    Log.d(TAG, "WS_DEDUPE: Duplicate WS message skipped: $key")
                    return
                }
                Log.d(TAG, "WS_FORWARD: forwarding message key=$key type=${message.type} from=${message.senderEmail} to=${message.receiverEmail}")
                val sent = messageChannel.trySend(message)
                Log.d(TAG, "WS_FORWARD_RESULT: key=$key sentToChannel=$sent")
            } catch (e: Exception) { Log.w(TAG, "Failed to forward parsed message: ${e.message}") }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse WS message", e)
        }
    }

    // Update sendMessage to send the compact payload the server expects for outgoing direct
    // chat messages: { "type":"MESSAGE", "content":"..." }
    fun sendMessage(senderEmail: String, receiverEmail: String, content: String, messageId: String): Boolean {
        val ws = webSocket
        if (ws == null || _connectionState.value != ConnectionState.CONNECTED) {
            synchronized(outgoingLock) { outgoingQueue.add(DirectChatMessageRequest(senderEmail, receiverEmail, content, messageId)); Log.d(TAG, "Not connected; queuing outgoing direct message and attempting connect") }
            connect(senderEmail, receiverEmail)
            return true
        }

        return try {
            val outgoing = OutgoingSimpleMessage(type = "MESSAGE", content = content, messageId = messageId)
            val json = gson.toJson(outgoing)
            val sent = ws.send(json)
            Log.d(TAG, "Sent outgoing direct message (messageId=$messageId) sent=$sent json=$json")
            sent
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send WS message", e)
            false
        }
    }

    fun sendDelete(messageIds: List<String>, conversationWith: String, deletedBy: String? = null): Boolean {
        val ws = webSocket
        if (ws == null || _connectionState.value != ConnectionState.CONNECTED) {
            synchronized(outgoingLock) { outgoingDeleteQueue.add(DeleteMessageRequest(messageIds, conversationWith, deletedBy)); Log.d(TAG, "Not connected; queuing delete request and attempting connect") }
            connect()
            return true
        }

        return try {
            val singleUuid = messageIds.firstOrNull()
            val base = mutableMapOf<String, Any>("type" to "DELETE", "messageIds" to messageIds, "conversationWith" to conversationWith)
            if (!deletedBy.isNullOrBlank()) base["deletedBy"] = deletedBy
            if (!singleUuid.isNullOrBlank()) base["messageUuid"] = singleUuid
            val json = gson.toJson(base)
            if (!singleUuid.isNullOrBlank()) synchronized(pendingDeleteMap) { pendingDeleteMap[singleUuid] = messageIds }
            val sent = ws.send(json)
            Log.d(TAG, "Sent delete request ids=$messageIds sent=$sent json=$json")
            sent
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send delete request", e)
            false
        }
    }

    private data class OutgoingSimpleMessage(
        val type: String = "MESSAGE",
        val content: String,
        val messageId: String? = null
    )

    fun disconnect() {
        Log.d(TAG, "Disconnecting WebSocket")
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        connectedSender = null
        connectedReceiver = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    sealed class ConnectionState {
        object DISCONNECTED : ConnectionState()
        object CONNECTING : ConnectionState()
        object CONNECTED : ConnectionState()
        data class ERROR(val message: String) : ConnectionState()
    }
}

// Request format to send message
data class DirectChatMessageRequest(
    @SerializedName("senderEmail")
    val senderEmail: String,

    @SerializedName("receiverEmail")
    val receiverEmail: String,

    @SerializedName("content")
    val content: String,

    @SerializedName("messageId")
    val messageId: String
)

// Response format from server — tolerant model
data class DirectChatMessage(
    @SerializedName("id")
    val id: String? = null,

    @SerializedName("senderEmail")
    val senderEmail: String? = null,

    @SerializedName("receiverEmail")
    val receiverEmail: String? = null,

    @SerializedName("content")
    val content: String? = null,

    @SerializedName("timestamp")
    val timestamp: String? = null,

    @SerializedName("type")
    val type: String? = null,

    @SerializedName("messageId")
    val messageIdElement: JsonElement? = null,

    @SerializedName("senderUsername")
    val senderUsername: String? = null,

    @SerializedName("receiverUsername")
    val receiverUsername: String? = null,

    @SerializedName("readStatus")
    val readStatus: Boolean? = null,

    @SerializedName("senderDeleted")
    val senderDeleted: Boolean? = null,

    @SerializedName("receiverDeleted")
    val receiverDeleted: Boolean? = null,

    @SerializedName("deletedBy")
    val deletedBy: String? = null,

    @SerializedName("messageIds")
    val messageIds: List<String>? = null
)

// History wrapper for server-sent conversation history
data class DirectChatHistory(
    @SerializedName("chatWith") val chatWith: String,
    @SerializedName("messages") val messages: List<DirectChatMessage>? = null
)

// Chat summary / rooms listing
data class ChatSummaryRoom(
    @SerializedName("chatPartner") val chatPartner: String,
    @SerializedName("unreadCount") val unreadCount: Int = 0
)

data class ChatSummary(
    @SerializedName("type") val type: String?,
    @SerializedName("rooms") val rooms: List<ChatSummaryRoom>
)
