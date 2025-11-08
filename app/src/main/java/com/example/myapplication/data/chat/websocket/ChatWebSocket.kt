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


class ChatWebSocket(private val baseUrl: String) {
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

    private fun buildUrl(roomCode: String, email: String) = "$baseUrl?roomCode=${roomCode}&email=${email}"

    // Try to extract a nested string value from JSON by scanning common keys and nested objects.
    private fun extractStringFromJson(raw: String, candidates: List<String>): String? {
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

            // 3) Last resort: return the first non-empty string primitive anywhere in JSON
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

            findAnyString(el)
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
            val req = Request.Builder().url(url).build()
            ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("ChatWebSocket", "Connected to $url")
                    reconnectAttempts = 0
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        Log.d("ChatWebSocket", "RAW INCOMING: $text")
                        val wsMsg = try { gson.fromJson(text, WSChatMessage::class.java) } catch (e: Exception) {
                            Log.w("ChatWebSocket", "Failed to parse WSChatMessage: ${e.message}")
                            null
                        }
                        if (wsMsg != null) {
                            val id = wsMsg.id ?: wsMsg.messageId ?: java.util.UUID.randomUUID().toString()
                            var senderId = wsMsg.senderId ?: wsMsg.senderEmail ?: ""
                            var recipientId = wsMsg.recipientId ?: wsMsg.receiverEmail ?: ""
                            var content = wsMsg.content ?: ""
                            // If content is blank, try common alternative keys in the raw JSON
                            val candidateContentKeys = listOf("content", "message", "text", "body", "data.content", "data.message", "payload.text")
                            if (content.isBlank()) {
                                content = extractStringFromJson(text, candidateContentKeys) ?: ""
                                if (content.isBlank()) {
                                    // fallback: try to summarize data or payload so UI shows something
                                    content = extractDataSummary(text) ?: "[non-text message]"
                                }
                            }

                            // If content itself is a JSON blob (server nested the message), try to extract inner text
                            try {
                                val trimmed = content.trim()
                                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                                    val inner = extractStringFromJson(content, candidateContentKeys)
                                    if (!inner.isNullOrBlank()) {
                                        content = inner
                                    }
                                }
                            } catch (_: Exception) {}

                            // If senderId blank, try other keys
                            if (senderId.isBlank()) {
                                val candidateSenderKeys = listOf("senderId", "senderEmail", "sender", "from", "createdByEmail", "creatorEmail", "data.senderEmail", "data.sender")
                                senderId = extractStringFromJson(text, candidateSenderKeys) ?: senderId
                            }
                            if (recipientId.isBlank()) {
                                val candidateRecipientKeys = listOf("recipientId", "receiverEmail", "to", "data.receiverEmail", "data.recipient")
                                recipientId = extractStringFromJson(text, candidateRecipientKeys) ?: recipientId
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
                                senderName = senderId,
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
                    Log.d("ChatWebSocket", "Closed: $code / $reason (closedByClient=$isClosedByClient)")
                    if (!isClosedByClient) scheduleReconnectWithBackoff()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("ChatWebSocket", "Failure: ${t.message}", t)
                    if (!isClosedByClient) scheduleReconnectWithBackoff()
                }
            })
        } catch (t: Throwable) {
            Log.e("ChatWebSocket", "connect error: ${t.message}", t)
            if (!isClosedByClient) scheduleReconnectWithBackoff()
        }
    }

    private fun scheduleReconnectWithBackoff() {
        reconnectAttempts = (reconnectAttempts + 1).coerceAtMost(10)
        val backoff = (1000L * Math.pow(2.0, (reconnectAttempts - 1).toDouble())).toLong().coerceAtMost(60_000L)
        Log.i("ChatWebSocket", "Scheduling reconnect attempt $reconnectAttempts in ${backoff}ms")
        scope.launch {
            try {
                kotlinx.coroutines.delay(backoff)
                val room = currentRoomCode
                val email = localEmail
                if (!isClosedByClient && !room.isNullOrBlank() && !email.isNullOrBlank()) {
                    Log.i("ChatWebSocket", "Reconnecting (attempt $reconnectAttempts) to room=$room")
                    connect(room, email)
                }
            } catch (e: Exception) {
                Log.w("ChatWebSocket", "Reconnect scheduler failed: ${e.message}")
            }
        }
    }

    fun sendMessage(msg: ChatMessage) {
        try {
            val json = gson.toJson(msg)
            ws?.send(json) ?: Log.w("ChatWebSocket", "WebSocket not connected; cannot send")
        } catch (t: Throwable) {
            Log.e("ChatWebSocket", "sendMessage error: ${t.message}", t)
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
