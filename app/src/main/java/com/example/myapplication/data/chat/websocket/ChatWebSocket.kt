package com.example.myapplication.data.chat.websocket

import android.util.Log
import com.example.myapplication.data.chat.model.ChatMessage
import com.example.myapplication.data.chat.model.WSChatMessage
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import kotlin.math.pow


class ChatWebSocket(private val baseUrl: String, private val authToken: String? = null) {
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private val gson = Gson()
    // store local email used for connection so we can mark incoming messages as from-me
    private var localEmail: String? = null
    private var currentRoomCode: String? = null
    private var isClosedByClient = false
    private var reconnectAttempts = 0

    private val _incoming = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<ChatMessage> = _incoming

    private val scope = CoroutineScope(Dispatchers.IO)

    // Outgoing message queue used while socket is not connected. Thread-safe via synchronization on this list.
    private val outgoingQueue: MutableList<ChatMessage> = mutableListOf()
    private val queueLock = Any()
    // Outgoing delete queue (for when socket is not yet connected)
    private val outgoingDeleteQueue: MutableList<DeleteMessageRequest> = mutableListOf()

    private data class DeleteMessageRequest(val messageIds: List<String>, val conversationWith: String, val deletedBy: String?)

    private fun buildUrl(roomCode: String, email: String): String {
        val base = baseUrl.trimEnd('/')
        val rc = java.net.URLEncoder.encode(roomCode, "UTF-8")
        val em = java.net.URLEncoder.encode(email, "UTF-8")
        return "$base?roomCode=$rc&email=$em"
    }

    // Try to extract a nested primitive value from JSON by scanning common keys and nested objects.
    // Accepts string or numeric primitives and returns their string form.
    // If allowAnyString==true, the function will as a last resort return the first non-empty string
    // primitive found anywhere in the JSON. By default this is disabled to avoid accidentally
    // returning unrelated fields (e.g. a top-level "type":"MESSAGE"). Callers that are
    // extracting free-form content should pass allowAnyString=true.
    private fun extractStringFromJson(raw: String, candidates: List<String>, allowAnyString: Boolean = false): String? {
        return try {
            val el: JsonElement = JsonParser.parseString(raw)
            // 1) Try dotted key paths first
            for (key in candidates) {
                val parts = key.split('.')
                var cur: JsonElement? = el
                var ok = true
                for (p in parts) {
                    if (cur == null) { ok = false; break }
                    if (cur.isJsonObject) {
                        val o = cur.asJsonObject
                        cur = if (o.has(p)) o.get(p) else { ok = false; break }
                    } else { ok = false; break }
                }
                if (ok && cur != null && cur.isJsonPrimitive && cur.asJsonPrimitive.isString) {
                    val s = cur.asString
                    if (!s.isNullOrBlank()) return s
                }
            }

            // 2) If not found, recursively search object/array nodes for candidate keys anywhere
            fun recurseFind(element: JsonElement, keys: Set<String>): String? {
                try {
                    if (element.isJsonObject) {
                        val obj = element.asJsonObject
                        // check direct keys
                        for ((k, v) in obj.entrySet()) {
                            if (k in keys && v.isJsonPrimitive && v.asJsonPrimitive.isString) {
                                val s = v.asString
                                if (!s.isNullOrBlank()) return s
                            }
                        }
                        // recurse
                        for ((_, v) in obj.entrySet()) {
                            val found = recurseFind(v, keys)
                            if (!found.isNullOrBlank()) return found
                        }
                    } else if (element.isJsonArray) {
                        for (it in element.asJsonArray) {
                            val found = recurseFind(it, keys)
                            if (!found.isNullOrBlank()) return found
                        }
                    } // primitive string nodes are handled by the findAnyString fallback if needed
                } catch (_: Exception) {}
                return null
            }

            val keySet = candidates.map { it.substringAfterLast('.') }.toSet()
            val found = recurseFind(el, keySet)
            if (!found.isNullOrBlank()) return found

            // 3) Optionally as a last resort, return the first non-empty string primitive anywhere
            // in the JSON. This is disabled by default to avoid returning unrelated fields like
            // a top-level "type" value; callers that need this permissive behavior can opt-in.
            if (allowAnyString) {
                fun findAnyString(element: JsonElement): String? {
                    try {
                        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                            val s = element.asString
                            if (!s.isNullOrBlank()) return s
                        } else if (element.isJsonObject) {
                            for ((_, v) in element.asJsonObject.entrySet()) {
                                val f = findAnyString(v)
                                if (!f.isNullOrBlank()) return f
                            }
                        } else if (element.isJsonArray) {
                            for (it in element.asJsonArray) {
                                val f = findAnyString(it)
                                if (!f.isNullOrBlank()) return f
                            }
                        }
                    } catch (_: Exception) {}
                    return null
                }
                return findAnyString(el)
            }

            null
        } catch (_: Exception) { null }
    }

    // If no primitive string found, create a short summary from `data` field or entire payload
    private fun extractDataSummary(raw: String): String? {
        return try {
            val el = JsonParser.parseString(raw)
            if (!el.isJsonObject) return null
            val obj = el.asJsonObject
            val dataEl = if (obj.has("data")) obj.get("data") else null
            val target = dataEl ?: el
            val json = gson.toJson(target)
            val summary = json.trim().replace("\n", " ").replace("\\s+".toRegex(), " ")
            if (summary.length > 200) summary.substring(0, 197) + "..." else summary
        } catch (_: Exception) { null }
    }

    fun connect(roomCode: String, email: String) {
        // store connection context for reconnection
        currentRoomCode = roomCode
        localEmail = email
        isClosedByClient = false

        try {
            val url = buildUrl(roomCode, email)
            val rb = Request.Builder().url(url)
            // Add Authorization header if token provided (masked value logged elsewhere)
            authToken?.takeIf { it.isNotBlank() }?.let { rb.addHeader("Authorization", "Bearer $it") }
            val req = rb.build()
            ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("ChatWebSocket", "Connected to $url")
                    reconnectAttempts = 0
                    // flush any queued outgoing messages now that socket is open
                    flushOutgoingQueue()
                    // flush any queued delete requests
                    synchronized(queueLock) {
                        if (outgoingDeleteQueue.isNotEmpty()) {
                            Log.d("ChatWebSocket", "Flushing ${outgoingDeleteQueue.size} queued delete requests")
                            val copy = ArrayList(outgoingDeleteQueue)
                            outgoingDeleteQueue.clear()
                            for (del in copy) {
                                try {
                                    val payload = if (!del.deletedBy.isNullOrBlank()) mapOf("type" to "DELETE", "messageIds" to del.messageIds, "conversationWith" to del.conversationWith, "deletedBy" to del.deletedBy) else mapOf("type" to "DELETE", "messageIds" to del.messageIds, "conversationWith" to del.conversationWith)
                                    val json = gson.toJson(payload)
                                    val sent = webSocket.send(json)
                                    Log.d("ChatWebSocket", "Flushed queued delete (ids=${del.messageIds}) sent=$sent json=$json")
                                } catch (e: Exception) {
                                    Log.e("ChatWebSocket", "Failed to send queued delete request: ${e.message}")
                                }
                            }
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        Log.d("ChatWebSocket", "RAW INCOMING: $text")

                        // Parse top-level JSON to handle "type"-based payloads (DELETE, history, system, etc.)
                        val rootEl = try { JsonParser.parseString(text) } catch (e: Exception) { null }
                        val topType: String? = try {
                            if (rootEl != null && rootEl.isJsonObject && rootEl.asJsonObject.has("type")) rootEl.asJsonObject.get("type").asString else null
                        } catch (_: Exception) { null }

                        if (topType != null) {
                            when (topType.uppercase()) {
                                "DELETE" -> {
                                    // map delete notification to a ChatMessage with same id (prefer messageId then tempId)
                                    val messageId = extractStringFromJson(text, listOf("messageId", "id"))
                                    val tempId = extractStringFromJson(text, listOf("tempId"))
                                    val resolvedId = messageId ?: tempId ?: return
                                    val deletedBy = extractStringFromJson(text, listOf("deletedBy", "deletedByEmail", "deletedById", "user")) ?: ""
                                    val ts = try {
                                        val t = extractStringFromJson(text, listOf("timestamp"))
                                        if (!t.isNullOrBlank()) {
                                            try { java.time.Instant.parse(t).toEpochMilli() } catch (_: Exception) { t.toLongOrNull() ?: System.currentTimeMillis() }
                                        } else System.currentTimeMillis()
                                    } catch (_: Exception) { System.currentTimeMillis() }

                                    val mapped = ChatMessage(
                                        id = resolvedId,
                                        conversationId = currentRoomCode ?: "",
                                        senderId = deletedBy,
                                        senderName = deletedBy,
                                        senderAvatar = null,
                                        recipientId = null,
                                        content = "Deleted",
                                        timestamp = ts,
                                        status = com.example.myapplication.data.chat.model.MessageStatus.DELIVERED,
                                        isFromMe = deletedBy.equals(localEmail ?: "", ignoreCase = true),
                                        senderDeleted = true,
                                        receiverDeleted = true
                                    )
                                    Log.d("ChatWebSocket", "EMIT DELETE MAPPED: $mapped")
                                    scope.launch { _incoming.emit(mapped) }
                                    return
                                }

                                "HISTORY" -> {
                                    try {
                                        if (rootEl != null && rootEl.isJsonObject) {
                                            val obj = rootEl.asJsonObject
                                            if (obj.has("messages") && obj.get("messages").isJsonArray) {
                                                for (it in obj.get("messages").asJsonArray) {
                                                    try {
                                                        val m = it.asJsonObject
                                                        val id = when {
                                                            m.has("messageId") -> m.get("messageId").asString
                                                            m.has("id") -> m.get("id").asString
                                                            else -> java.util.UUID.randomUUID().toString()
                                                        }
                                                        val sender = when {
                                                            m.has("senderUsername") -> m.get("senderUsername").asString
                                                            m.has("senderEmail") -> m.get("senderEmail").asString
                                                            m.has("senderId") -> m.get("senderId").asString
                                                            else -> extractStringFromJson(m.toString(), listOf("senderEmail", "senderId", "sender")) ?: ""
                                                        }
                                                        val recipient = when {
                                                            m.has("receiverEmail") -> m.get("receiverEmail").asString
                                                            m.has("recipientId") -> m.get("recipientId").asString
                                                            else -> extractStringFromJson(m.toString(), listOf("receiverEmail", "recipientId", "to")) ?: ""
                                                        }
                                                        val content = when {
                                                            m.has("content") -> if (m.get("content").isJsonPrimitive) m.get("content").asString else m.get("content").toString()
                                                            m.has("message") -> m.get("message").asString
                                                            else -> extractStringFromJson(m.toString(), listOf("content", "message", "text")) ?: ""
                                                        }
                                                        val ts = try {
                                                            if (m.has("timestamp")) {
                                                                val t = m.get("timestamp").asString
                                                                try { java.time.Instant.parse(t).toEpochMilli() } catch (_: Exception) { t.toLongOrNull() ?: System.currentTimeMillis() }
                                                            } else System.currentTimeMillis()
                                                        } catch (_: Exception) { System.currentTimeMillis() }

                                                        // Prefer explicit roomCode in the HISTORY envelope, then chatWith, then currentRoomCode
                                                        val convId = when {
                                                            obj.has("roomCode") -> obj.get("roomCode").asString
                                                            obj.has("chatWith") -> obj.get("chatWith").asString
                                                            else -> currentRoomCode ?: ""
                                                        }
                                                        val mapped = ChatMessage(
                                                            id = id,
                                                            conversationId = convId,
                                                            senderId = sender,
                                                            senderName = sender,
                                                            senderAvatar = null,
                                                            recipientId = recipient,
                                                            content = content,
                                                            timestamp = ts,
                                                            status = com.example.myapplication.data.chat.model.MessageStatus.DELIVERED,
                                                            isFromMe = sender.equals(localEmail ?: "", ignoreCase = true)
                                                        )
                                                        scope.launch { _incoming.emit(mapped) }
                                                    } catch (inner: Exception) {
                                                        Log.w("ChatWebSocket", "Failed map history item: ${inner.message}")
                                                    }
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w("ChatWebSocket", "Failed to parse history: ${e.message}")
                                    }
                                    return
                                }

                                "SYSTEM", "CHATSUMMARY" -> {
                                    // These are informational; UI may listen elsewhere. Log and ignore for message flow.
                                    Log.d("ChatWebSocket", "Ignoring system/chatSummary payload: $text")
                                    return
                                }

                                else -> {
                                    // fallthrough to normal parsing below
                                }
                            }
                        }

                        // Fallback: try map using WSChatMessage DTO then tolerant extraction
                        val wsMsg = try { gson.fromJson(text, WSChatMessage::class.java) } catch (e: Exception) {
                            Log.w("ChatWebSocket", "Failed to parse WSChatMessage: ${e.message}")
                            null
                        }

                        if (wsMsg != null) {
                            // Prefer server 'id', then 'messageId', then client's optimistic 'tempId' so we can
                            // correlate server echoes with optimistic outgoing messages.
                            val id = wsMsg.id ?: wsMsg.messageId ?: wsMsg.tempId ?: java.util.UUID.randomUUID().toString()
                            var senderId = wsMsg.senderId ?: wsMsg.senderEmail ?: ""
                            var recipientId = wsMsg.recipientId ?: wsMsg.receiverEmail ?: ""
                            // prefer explicit 'content' then fallback to 'message' key
                            var content = wsMsg.content ?: wsMsg.message ?: ""
                            // If content is blank, try common alternative keys in the raw JSON
                            val candidateContentKeys = listOf("content", "message", "text", "body", "data.content", "data.message", "payload.text")
                            if (content.isBlank()) {
                                content = extractStringFromJson(text, candidateContentKeys, /*allowAnyString=*/ false) ?: ""
                                if (content.isBlank()) {
                                    if (wsMsg.type?.equals("MESSAGE", ignoreCase = true) == true) {
                                        // don't fill with full JSON; leave blank so UI can match optimistic text
                                        content = wsMsg.message ?: ""
                                    } else {
                                        // Non-message types: produce a short summary for display
                                        content = extractDataSummary(text) ?: "[non-text message]"
                                    }
                                }
                            }

                            // If content itself is a JSON blob (server nested the message), try to extract inner text
                            try {
                                val trimmed = content.trim()
                                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                                    val inner = extractStringFromJson(content, candidateContentKeys, /*allowAnyString=*/ false)
                                    if (!inner.isNullOrBlank()) {
                                        content = inner
                                    }
                                }
                            } catch (_: Exception) {}

                            // If senderId blank, try other keys
                            if (senderId.isBlank()) {
                                val candidateSenderKeys = listOf("senderId", "senderEmail", "sender", "from", "createdByEmail", "creatorEmail", "data.senderEmail", "data.sender")
                                senderId = extractStringFromJson(text, candidateSenderKeys, /*allowAnyString=*/ false) ?: senderId
                            }
                            if (recipientId.isBlank()) {
                                val candidateRecipientKeys = listOf("recipientId", "receiverEmail", "to", "data.receiverEmail", "data.recipient")
                                recipientId = extractStringFromJson(text, candidateRecipientKeys, /*allowAnyString=*/ false) ?: recipientId
                            }

                            val timestamp = try {
                                if (!wsMsg.timestamp.isNullOrBlank()) {
                                    try { java.time.Instant.parse(wsMsg.timestamp).toEpochMilli() } catch (_: Exception) { wsMsg.timestamp.toLongOrNull() ?: System.currentTimeMillis() }
                                } else System.currentTimeMillis()
                            } catch (_: Exception) { System.currentTimeMillis() }

                            val mapped = ChatMessage(
                                id = id,
                                conversationId = wsMsg.conversationId ?: currentRoomCode ?: "",
                                senderId = senderId,
                                senderName = wsMsg.senderUsername ?: senderId,
                                senderAvatar = null,
                                recipientId = recipientId,
                                content = content,
                                timestamp = timestamp,
                                status = com.example.myapplication.data.chat.model.MessageStatus.DELIVERED,
                                isFromMe = senderId.equals(localEmail ?: "", ignoreCase = true)
                            )
                            Log.d("ChatWebSocket", "EMIT MAPPED: $mapped")
                            scope.launch { _incoming.emit(mapped) }
                        } else {
                            Log.w("ChatWebSocket", "Received null or invalid message")
                        }
                    } catch (e: Exception) {
                        Log.w("ChatWebSocket", "Failed to parse incoming: ${e.message}")
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    onMessage(webSocket, bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    super.onClosing(webSocket, code, reason)
                    Log.d("ChatWebSocket", "Closing: $code / $reason")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("ChatWebSocket", "Closed: $code / $reason (closedByClient=${'$'}isClosedByClient)")
                    if (!isClosedByClient) scheduleReconnectWithBackoff()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("ChatWebSocket", "Failure: ${'$'}{t.message}", t)
                    if (!isClosedByClient) scheduleReconnectWithBackoff()
                }
            })
        } catch (t: Throwable) {
            Log.e("ChatWebSocket", "connect error: ${'$'}{t.message}", t)
            if (!isClosedByClient) scheduleReconnectWithBackoff()
        }
    }

    // Send any queued messages (called from onOpen). Non-blocking; logs failures.
    private fun flushOutgoingQueue() {
        scope.launch {
            val pending = synchronized(queueLock) {
                val copy = outgoingQueue.toList()
                outgoingQueue.clear()
                copy
            }
            if (pending.isNotEmpty()) Log.d("ChatWebSocket", "Flushing ${pending.size} queued messages")
            for (m in pending) {
                try {
                    sendMessageImmediate(m)
                } catch (e: Exception) {
                    Log.w("ChatWebSocket", "Failed to send queued message ${m.id}: ${e.message}")
                }
            }
        }
    }

    // Internal helper to actually send now (assumes ws is non-null)
    private fun sendMessageImmediate(msg: ChatMessage) {
        try {
            val wsLocal = ws
            if (wsLocal == null) {
                Log.w("ChatWebSocket", "sendMessageImmediate: WebSocket null")
                // re-queue
                synchronized(queueLock) { outgoingQueue.add(msg) }
                return
            }

            val contentStr = msg.content ?: ""
            val conv = msg.conversationId.takeIf { !it.isNullOrBlank() } ?: currentRoomCode
            // For chat-room payloads we use "message" key for the textual body so server
            // receives {"type":"MESSAGE","message":"hello", ...}
            val outgoingMap = mutableMapOf<String, Any?>(
                "type" to "MESSAGE",
                "message" to contentStr,
                "messageId" to msg.id,
                // include a client tempId so some servers can echo it back for optimistic matching
                "tempId" to msg.id,
                "conversationId" to conv
            )

            val json = gson.toJson(outgoingMap)
            val sent = wsLocal.send(json)
            Log.d("ChatWebSocket", "Sent outgoing chat-room message (tempId=${msg.id}) sent=$sent json=$json")
        } catch (t: Throwable) {
            Log.e("ChatWebSocket", "sendMessageImmediate error: ${t.message}", t)
            // on failure, re-queue so it can be retried later
            synchronized(queueLock) { outgoingQueue.add(msg) }
        }
    }

    private fun scheduleReconnectWithBackoff() {
        reconnectAttempts = (reconnectAttempts + 1).coerceAtMost(10)
        val backoff = (1000L * 2.0.pow((reconnectAttempts - 1).toDouble())).toLong().coerceAtMost(60_000L)
        Log.i("ChatWebSocket", "Scheduling reconnect attempt ${'$'}reconnectAttempts in ${'$'}{backoff}ms")
        scope.launch {
            try {
                kotlinx.coroutines.delay(backoff)
                val room = currentRoomCode
                val email = localEmail
                if (!isClosedByClient && !room.isNullOrBlank() && !email.isNullOrBlank()) {
                    Log.i("ChatWebSocket", "Reconnecting (attempt ${'$'}reconnectAttempts) to room=${'$'}room")
                    connect(room, email)
                }
            } catch (e: Exception) {
                Log.w("ChatWebSocket", "Reconnect scheduler failed: ${'$'}{e.message}")
            }
        }
    }

    // Changed: send compact outgoing payload similar to DirectChat: {"type":"MESSAGE","content": <string>, "messageId": <id>, "conversationId": <roomCode>}
    // Include messageId and conversationId as top-level fields so server can map/route messages reliably.
    fun sendMessage(msg: ChatMessage) {
        try {
            val wsLocal = ws
            if (wsLocal == null) {
                // queue the message to be sent when socket opens
                synchronized(queueLock) {
                    outgoingQueue.add(msg)
                }
                Log.d("ChatWebSocket", "WebSocket not connected; queued message id=${msg.id}")
                return
            }

            // If socket is present but not open (some implementations), still queue
            // We try immediate send via helper which re-queues on failure.
            sendMessageImmediate(msg)
        } catch (t: Throwable) {
            Log.e("ChatWebSocket", "sendMessage error: ${t.message}", t)
            // ensure queued
            synchronized(queueLock) { outgoingQueue.add(msg) }
        }
    }

    /**
     * Send a delete request for messages in chat-room. Format: { "type":"DELETE", "messageIds":[...], "conversationWith":<roomCode>, "deletedBy": <optional> }
     * If socket is not connected the request is queued.
     */
    fun sendDelete(messageIds: List<String>, conversationWith: String, deletedBy: String? = null): Boolean {
        val wsLocal = ws
        if (wsLocal == null) {
            synchronized(queueLock) {
                outgoingDeleteQueue.add(DeleteMessageRequest(messageIds, conversationWith, deletedBy))
                Log.d("ChatWebSocket", "Not connected; queuing delete request and attempting connect")
            }
            // attempt connect (will flush queued deletes in onOpen)
            connect(conversationWith, localEmail ?: "")
            return true
        }

        return try {
            val payload = if (!deletedBy.isNullOrBlank()) mapOf("type" to "DELETE", "messageIds" to messageIds, "conversationWith" to conversationWith, "deletedBy" to deletedBy) else mapOf("type" to "DELETE", "messageIds" to messageIds, "conversationWith" to conversationWith)
            val json = gson.toJson(payload)
            val sent = wsLocal.send(json)
            Log.d("ChatWebSocket", "Sent delete request ids=$messageIds sent=$sent json=$json")
            sent
        } catch (e: Exception) {
            Log.e("ChatWebSocket", "Failed to send delete request", e)
            false
        }
    }

    fun close() {
        try {
            isClosedByClient = true
            ws?.close(1000, "Bye")
        } catch (_: Exception) {}
        try { client.dispatcher.executorService.shutdown() } catch (_: Exception) {}
    }
}
