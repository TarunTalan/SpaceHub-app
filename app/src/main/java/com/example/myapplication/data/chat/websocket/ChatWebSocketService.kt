package com.example.myapplication.data.chat.websocket

import android.content.Context
import android.util.Log
import android.annotation.SuppressLint
import com.example.myapplication.BuildConfig
import com.example.myapplication.data.network.SharedPrefsTokenStore
import com.example.myapplication.data.chat.model.WSChatMessage
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.UUID

class ChatWebSocketService private constructor(context: Context) {

    // Store only application context to avoid leaking an Activity/Service context via a static singleton.
    private val appContext: Context = context.applicationContext

    private val gson = Gson()
    private var sockJsClient: SockJsWebSocketClient? = null
    private var authToken: String? = null
    private val messageChannel = Channel<WSChatMessage>(Channel.BUFFERED)
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.DISCONNECTED)

    // Raw STOMP payloads (useful for voice signalling and other non-chat frames)
    private val stompPayloadChannel = Channel<String>(Channel.BUFFERED)
    val incomingStomp: Flow<String> = stompPayloadChannel.receiveAsFlow()

    val messages: Flow<WSChatMessage> = messageChannel.receiveAsFlow()
    val connectionState: StateFlow<ConnectionState> = _connectionState

    @SuppressLint("StaticFieldLeak")
    companion object {
        @Volatile
        private var INSTANCE: ChatWebSocketService? = null

        fun getInstance(context: Context): ChatWebSocketService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChatWebSocketService(context.applicationContext).also { INSTANCE = it }
            }
        }

        private const val TAG = "ChatWebSocket"
    }

    fun connect() {
        if (sockJsClient != null && sockJsClient?.isOpen == true) {
            Log.d(TAG, "SockJS WebSocket already connected")
            return
        }

        val tokenNullable = SharedPrefsTokenStore(appContext).getAccessToken()
        authToken = tokenNullable

        // Log masked token and BASE_URL for diagnostics (safe for null)
        try {
            val masked = tokenNullable?.let { t -> if (t.length <= 8) "****" else t.take(4) + "..." + t.takeLast(4) } ?: "****"
            Log.d(TAG, "Using auth token (masked): $masked")
            Log.d(TAG, "BuildConfig.BASE_URL = ${BuildConfig.BASE_URL}")
        } catch (_: Exception) {}

        // SockJS endpoint URL - Spring Boot typically uses /ws or /websocket
        // Build WebSocket base from origin (scheme + host + optional port) and use the '/ws' STOMP endpoint
        val baseUrl = try {
            val url = java.net.URL(BuildConfig.BASE_URL)
            val origin = StringBuilder().apply {
                append(if (url.protocol == "https") "wss" else if (url.protocol == "http") "ws" else url.protocol)
                append("://")
                append(url.host)
                if (url.port != -1) append(":").append(url.port)
            }.toString()
            // SockJS endpoint is typically exposed at /ws
            origin.trimEnd('/')
        } catch (_: Exception) {
            // Fallback: convert BASE_URL scheme and strip path
            BuildConfig.BASE_URL
                .replace("https://", "wss://")
                .replace("http://", "ws://")
                .trimEnd('/')
        }

        // Generate random server and session ID for SockJS protocol
        val serverId = (100..999).random()
        val sessionId = generateSessionId()
        val sockJsUrl = "$baseUrl/ws/$serverId/$sessionId/websocket"

        Log.d(TAG, "Connecting to SockJS WebSocket: $sockJsUrl")

        _connectionState.value = ConnectionState.CONNECTING

        try {
            sockJsClient = SockJsWebSocketClient(
                URI(sockJsUrl),
                onOpenCb = {
                    Log.d(TAG, "SockJS WebSocket connected successfully")
                    _connectionState.value = ConnectionState.CONNECTED
                    // Send STOMP CONNECT frame
                    sendStompConnect()
                },
                onMessageCb = { message: String ->
                    Log.d(TAG, "Received SockJS message: $message")
                    handleSockJsMessage(message)
                },
                onCloseCb = { code: Int, reason: String ->
                    Log.d(TAG, "SockJS WebSocket closed: $code - $reason")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    sockJsClient = null
                },
                onErrorCb = { error: String? ->
                    Log.e(TAG, "SockJS WebSocket error: $error")
                    _connectionState.value = ConnectionState.ERROR(error ?: "Connection failed")
                    sockJsClient = null
                }
            )
            sockJsClient?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to SockJS", e)
            _connectionState.value = ConnectionState.ERROR(e.message ?: "Connection failed")
        }
    }

    private fun sendStompConnect() {
        // Build STOMP CONNECT frame. Include Authorization header only if token is available.
        val sb = StringBuilder()
        sb.append("CONNECT\n")
        authToken?.takeIf { it.isNotBlank() }?.let {
            // add Authorization header in STOMP layer (server should read it from CONNECT headers)
            sb.append("Authorization: Bearer ").append(it).append('\n')
        }
        sb.append("accept-version:1.1,1.0\n")
        sb.append("heart-beat:10000,10000\n")
        // blank line to separate headers from body
        sb.append('\n')
        // STOMP frame terminator (null char)
        sb.append('\u0000')

        val stompFrame = sb.toString()
        try {
            sockJsClient?.send(stompFrame)
            Log.d(TAG, "Sent STOMP CONNECT frame")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send STOMP CONNECT frame", e)
        }
    }

    private fun handleSockJsMessage(message: String) {
        try {
            // Handle SockJS protocol frames
            when {
                message.startsWith("o") -> {
                    // Open frame - connection established
                    Log.d(TAG, "SockJS open frame received")
                }
                message.startsWith("h") -> {
                    // Heartbeat frame
                    Log.d(TAG, "SockJS heartbeat received")
                }
                message.startsWith("a") -> {
                    // Array of messages
                    val jsonArray = message.substring(1)
                    val messages = gson.fromJson(jsonArray, Array<String>::class.java)
                    messages?.forEach { processStompFrame(it) }
                }
                message.startsWith("c") -> {
                    // Close frame
                    Log.d(TAG, "SockJS close frame received")
                }
                else -> {
                    // Try to parse as STOMP frame directly
                    processStompFrame(message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle SockJS message", e)
        }
    }

    private fun processStompFrame(frame: String) {
        try {
            // STOMP frames may include headers followed by a blank line and then the body
            val body: String = when {
                frame.startsWith("\"") && frame.endsWith("\"") -> {
                    // Some SockJS transports wrap the STOMP frame string in quotes
                    frame.trim('"')
                }
                else -> frame
            }

            if (body.startsWith("CONNECTED")) {
                Log.d(TAG, "STOMP CONNECTED frame received")
                // Subscribe to chat topic
                subscribeToChat()
                return
            }

            if (body.startsWith("MESSAGE") || body.contains("\n\n")) {
                val idx = body.indexOf("\n\n")
                val payload = if (idx >= 0) {
                    // extract everything after the header separator and drop the trailing null
                    body.substring(idx + 2).replace("\u0000", "").trim()
                } else {
                    // No headers - treat whole body as payload (sometimes server sends raw JSON)
                    body.replace("\u0000", "").trim()
                }

                // Emit raw payload for consumers (voice, system, etc.)
                try { stompPayloadChannel.trySend(payload) } catch (_: Exception) {}

                if (payload.isBlank()) {
                    Log.w(TAG, "STOMP MESSAGE had empty payload")
                    return
                }

                // Try to interpret payload as JSON
                try {
                    val element: JsonElement = gson.fromJson(payload, JsonElement::class.java)

                    if (element.isJsonObject) {
                        val obj = element.asJsonObject
                        val type = obj.getAsJsonPrimitive("type")?.asString

                        when (type) {
                            "MESSAGE" -> {
                                var msg = gson.fromJson(obj, WSChatMessage::class.java)
                                // normalize: prefer 'content' then 'message' field for text
                                if (msg.content.isNullOrBlank()) {
                                    val alt = obj.getAsJsonPrimitive("message")?.asString
                                    if (!alt.isNullOrBlank()) {
                                        msg = msg.copy(content = alt)
                                    }
                                }
                                Log.d(TAG, "Incoming MESSAGE -> $msg")
                                messageChannel.trySend(msg)
                            }
                            "history" -> {
                                val arr = obj.getAsJsonArray("messages")
                                arr?.forEach { itElem ->
                                    try {
                                        var histMsg = gson.fromJson(itElem, WSChatMessage::class.java)
                                        if (histMsg.content.isNullOrBlank()) {
                                            val alt = itElem.asJsonObject.getAsJsonPrimitive("message")?.asString
                                            if (!alt.isNullOrBlank()) histMsg = histMsg.copy(content = alt)
                                        }
                                        // Ensure type is set so downstream UI knows this is an existing message
                                        val normalized = histMsg.copy(type = histMsg.type ?: "MESSAGE")
                                        Log.d(TAG, "History item -> $normalized")
                                        messageChannel.trySend(normalized)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to parse history item", e)
                                    }
                                }
                            }
                            "DELETE" -> {
                                // Convert delete payload into WSChatMessage with type=DELETE and messageId set
                                val deletedId = obj.getAsJsonPrimitive("messageId")?.asString
                                val ts = obj.getAsJsonPrimitive("timestamp")?.asString
                                val deletedMsg = WSChatMessage(
                                    messageId = deletedId,
                                    type = "DELETE",
                                    timestamp = ts
                                )
                                Log.d(TAG, "Incoming DELETE -> $deletedMsg")
                                messageChannel.trySend(deletedMsg)
                            }
                            else -> {
                                // system, chatSummary or unknown types - log and ignore or pass as generic
                                Log.d(TAG, "Unhandled STOMP payload type=$type payload=$payload")
                                // Optionally forward system/chatSummary as WSChatMessage with type set
                                if (type == "system" || type == "chatSummary") {
                                    val wrapper = WSChatMessage(type = type, content = payload)
                                    messageChannel.trySend(wrapper)
                                }
                            }
                        }
                    } else {
                        // Not an object - could be plain string
                        Log.d(TAG, "Received non-object STOMP payload: $payload")
                        // Wrap it as a message
                        val wrapper = WSChatMessage(type = "MESSAGE", content = payload)
                        messageChannel.trySend(wrapper)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse STOMP MESSAGE payload as JSON", e)
                    // send raw payload wrapped
                    val wrapper = WSChatMessage(type = "MESSAGE", content = payload)
                    messageChannel.trySend(wrapper)
                }

            } else if (body.startsWith("ERROR")) {
                Log.e(TAG, "STOMP ERROR frame: $frame")
            } else {
                // Unknown frame - log
                Log.d(TAG, "Unknown STOMP frame received: $frame")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process STOMP frame", e)
        }
    }

    private fun subscribeToChat() {
        // Subscribe to user's personal chat queue
        val subscribeFrame = """
            SUBSCRIBE
            id:sub-0
            destination:/user/queue/messages
            
            ${'\u0000'}
        """.trimIndent()
        sockJsClient?.send(subscribeFrame)
        Log.d(TAG, "Subscribed to /user/queue/messages")
    }

    // New: subscribe to any destination and return a subscription id that can be used to unsubscribe
    fun subscribeTo(destination: String, subscriptionId: String = UUID.randomUUID().toString()): String {
        try {
            val stompFrame = """
                SUBSCRIBE
                id:$subscriptionId
                destination:$destination

                ${'\u0000'}
            """.trimIndent()
            sockJsClient?.send(stompFrame)
            Log.d(TAG, "Sent SUBSCRIBE -> id=$subscriptionId destination=$destination")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SUBSCRIBE frame to $destination", e)
        }
        return subscriptionId
    }

    fun unsubscribe(subscriptionId: String?) {
        if (subscriptionId.isNullOrBlank()) return
        try {
            val stompFrame = """
                UNSUBSCRIBE
                id:$subscriptionId

                ${'\u0000'}
            """.trimIndent()
            sockJsClient?.send(stompFrame)
            Log.d(TAG, "Sent UNSUBSCRIBE -> id=$subscriptionId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send UNSUBSCRIBE frame id=$subscriptionId", e)
        }
    }

    // New helper: send arbitrary JSON to a STOMP destination (used by voice signalling)
    fun sendToDestination(destination: String, payloadJson: String) {
        try {
            val stompFrame = """
                SEND
                destination:$destination
                content-type:application/json
                
                $payloadJson${'\u0000'}
            """.trimIndent()
            sockJsClient?.send(stompFrame)
            Log.d(TAG, "Sent STOMP SEND -> destination=$destination payload=$payloadJson")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send STOMP SEND frame to $destination", e)
        }
    }

    fun sendMessage(message: WSChatMessage) {
        // Ensure we send minimal payload expected by server for outgoing messages
        val payloadObj = JsonObject()

        // Determine text value: prefer 'content' field
        val text = message.content?.takeIf { it.isNotBlank() }

        if (!text.isNullOrBlank()) {
            // include both keys to satisfy either server mapping
            payloadObj.addProperty("type", message.type ?: "MESSAGE")
            payloadObj.addProperty("content", text)
            payloadObj.addProperty("message", text)

            message.conversationId?.let { payloadObj.addProperty("conversationId", it) }
            message.recipientId?.let { payloadObj.addProperty("recipientId", it) }
            message.receiverEmail?.let { payloadObj.addProperty("receiverEmail", it) }
            message.id?.let { payloadObj.addProperty("id", it) }
            message.messageId?.let { payloadObj.addProperty("messageId", it) }
            message.senderId?.let { payloadObj.addProperty("senderId", it) }
        } else {
            // Fallback: send full serialized object but ensure 'type' exists and copy textual keys
            val asJson = gson.toJsonTree(message).asJsonObject
            if (!asJson.has("type")) asJson.addProperty("type", message.type ?: "MESSAGE")
            // If server expects 'message' key and we have 'content', copy it
            if (!asJson.has("message") && asJson.has("content")) {
                asJson.add("message", asJson.get("content"))
            }
            // Merge into payloadObj
            for ((key, value) in asJson.entrySet()) {
                payloadObj.add(key, value)
            }
        }

        val json = gson.toJson(payloadObj)
        val stompFrame = """
            SEND
            destination:/app/chat.send
            content-type:application/json
            
            $json${'\u0000'}
        """.trimIndent()

        sockJsClient?.send(stompFrame)
        Log.d(TAG, "Sending STOMP message: $json")
    }

    fun sendTypingIndicator(recipientId: String, conversationId: String) {
        val message = WSChatMessage(
            type = "typing",
            conversationId = conversationId,
            senderId = "",
            recipientId = recipientId
        )
        sendMessage(message)
    }

    fun markAsDelivered(messageId: String, conversationId: String) {
        val message = WSChatMessage(
            type = "delivered",
            messageId = messageId,
            conversationId = conversationId,
            senderId = "",
            recipientId = ""
        )
        sendMessage(message)
    }

    fun markAsRead(messageId: String, conversationId: String) {
        val message = WSChatMessage(
            type = "read",
            messageId = messageId,
            conversationId = conversationId,
            senderId = "",
            recipientId = ""
        )
        sendMessage(message)
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting SockJS WebSocket")
        try {
            // Send STOMP DISCONNECT frame
            val disconnectFrame = """
                DISCONNECT
                
                ${'\u0000'}
            """.trimIndent()
            sockJsClient?.send(disconnectFrame)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending disconnect", e)
        }
        sockJsClient?.close()
        sockJsClient = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun generateSessionId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..8).map { chars.random() }.joinToString("")
    }

    // WebSocket client for SockJS protocol
    private inner class SockJsWebSocketClient(
        uri: URI,
        private val onOpenCb: () -> Unit,
        private val onMessageCb: (String) -> Unit,
        private val onCloseCb: (Int, String) -> Unit,
        private val onErrorCb: (String?) -> Unit
    ) : WebSocketClient(
         uri,
         Draft_6455(),
         // Add handshake headers: set Sec-WebSocket-Protocol and Origin
         run {
             val headers = mutableMapOf<String, String>()
             headers["Sec-WebSocket-Protocol"] = "v10.stomp,v11.stomp"
             // Use only origin (scheme + host + optional port) rather than full BASE_URL path
             try {
                 val url = java.net.URL(BuildConfig.BASE_URL)
                 val origin = StringBuilder().apply {
                     append(url.protocol)
                     append("://")
                     append(url.host)
                     if (url.port != -1) append(":").append(url.port)
                 }.toString()
                 headers["Origin"] = origin
             } catch (_: Exception) {
                 headers["Origin"] = BuildConfig.BASE_URL
             }
             // Include Authorization in handshake if token available (some servers require it at WebSocket upgrade)
             try {
                 val token = authToken ?: SharedPrefsTokenStore(appContext).getAccessToken()
                 if (!token.isNullOrBlank()) {
                     headers["Authorization"] = "Bearer $token"
                 }
             } catch (_: Exception) {
             }
             headers
         },
         0
     ) {

         override fun onOpen(handshakedata: ServerHandshake?) {
             Log.d(TAG, "SockJS connection opened")
             onOpenCb()
         }

         override fun onMessage(message: String?) {
             message?.let { onMessageCb(it) }
         }

         override fun onClose(code: Int, reason: String?, remote: Boolean) {
             Log.d(TAG, "SockJS connection closed: $code - $reason")
             onCloseCb(code, reason ?: "Unknown")
         }

         override fun onError(ex: Exception?) {
             Log.e(TAG, "SockJS connection error", ex)
             onErrorCb(ex?.message)
         }
     }

    sealed class ConnectionState {
        object DISCONNECTED : ConnectionState()
        object CONNECTING : ConnectionState()
        object CONNECTED : ConnectionState()
        data class ERROR(val message: String) : ConnectionState()
    }
}
