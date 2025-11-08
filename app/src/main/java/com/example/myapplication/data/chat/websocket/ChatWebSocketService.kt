package com.example.myapplication.data.chat.websocket

import android.content.Context
import android.util.Log
import com.example.myapplication.BuildConfig
import com.example.myapplication.data.network.SharedPrefsTokenStore
import com.google.gson.Gson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

class ChatWebSocketService private constructor(private val context: Context) {

    private val gson = Gson()
    private var sockJsClient: SockJsWebSocketClient? = null
    private val messageChannel = Channel<com.example.myapplication.data.chat.model.WSChatMessage>(Channel.BUFFERED)
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.DISCONNECTED)

    val messages: Flow<com.example.myapplication.data.chat.model.WSChatMessage> = messageChannel.receiveAsFlow()
    val connectionState: StateFlow<ConnectionState> = _connectionState

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

        val token = SharedPrefsTokenStore(context).getAccessToken()
        if (token.isNullOrEmpty()) {
            Log.e(TAG, "No auth token available")
            _connectionState.value = ConnectionState.ERROR("No auth token")
            return
        }

        // Log masked token and BASE_URL for diagnostics
        try {
            val masked = if (token.length <= 8) "****" else token.take(4) + "..." + token.takeLast(4)
            Log.d(TAG, "Using auth token (masked): $masked")
            Log.d(TAG, "BuildConfig.BASE_URL = ${BuildConfig.BASE_URL}")
        } catch (_: Exception) {}

        // SockJS endpoint URL - Spring Boot typically uses /ws or /websocket
        val baseUrl = BuildConfig.BASE_URL
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/')

        // Generate random server and session ID for SockJS protocol
        val serverId = (100..999).random()
        val sessionId = generateSessionId()
        val sockJsUrl = "$baseUrl/ws/$serverId/$sessionId/websocket"

        Log.d(TAG, "Connecting to SockJS WebSocket: $sockJsUrl")

        _connectionState.value = ConnectionState.CONNECTING

        try {
            sockJsClient = SockJsWebSocketClient(
                URI(sockJsUrl),
                token,
                onOpen = {
                    Log.d(TAG, "SockJS WebSocket connected successfully")
                    _connectionState.value = ConnectionState.CONNECTED
                    // Send STOMP CONNECT frame
                    sendStompConnect()
                },
                onMessage = { message ->
                    Log.d(TAG, "Received SockJS message: $message")
                    handleSockJsMessage(message)
                },
                onClose = { code, reason ->
                    Log.d(TAG, "SockJS WebSocket closed: $code - $reason")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    sockJsClient = null
                },
                onError = { error ->
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
        val token = SharedPrefsTokenStore(context).getAccessToken() ?: ""
        val stompFrame = """
            CONNECT
            accept-version:1.1,1.0
            heart-beat:10000,10000
            Authorization:Bearer $token
            
            ${'\u0000'}
        """.trimIndent()
        sockJsClient?.send(stompFrame)
        Log.d(TAG, "Sent STOMP CONNECT frame")
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
            when {
                frame.startsWith("CONNECTED") -> {
                    Log.d(TAG, "STOMP CONNECTED frame received")
                    // Subscribe to chat topic
                    subscribeToChat()
                }
                frame.startsWith("MESSAGE") -> {
                    // Extract message body from STOMP frame
                    val bodyIndex = frame.indexOf("\n\n")
                    if (bodyIndex > 0) {
                        val body = frame.substring(bodyIndex + 2).replace("\u0000", "")
                        val chatMessage = gson.fromJson(body, com.example.myapplication.data.chat.model.WSChatMessage::class.java)
                        messageChannel.trySend(chatMessage)
                    }
                }
                frame.startsWith("ERROR") -> {
                    Log.e(TAG, "STOMP ERROR frame: $frame")
                }
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

    fun sendMessage(message: com.example.myapplication.data.chat.model.WSChatMessage) {
        val json = gson.toJson(message)
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
        val message = com.example.myapplication.data.chat.model.WSChatMessage(
            type = "typing",
            conversationId = conversationId,
            senderId = "", // Will be set by server
            recipientId = recipientId
        )
        sendMessage(message)
    }

    fun markAsDelivered(messageId: String, conversationId: String) {
        val message = com.example.myapplication.data.chat.model.WSChatMessage(
            type = "delivered",
            messageId = messageId,
            conversationId = conversationId,
            senderId = "",
            recipientId = ""
        )
        sendMessage(message)
    }

    fun markAsRead(messageId: String, conversationId: String) {
        val message = com.example.myapplication.data.chat.model.WSChatMessage(
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
    private class SockJsWebSocketClient(
        uri: URI,
        @Suppress("unused") private val token: String,
        private val onOpen: () -> Unit,
        @Suppress("unused") private val onMessage: (String) -> Unit,
        private val onClose: (Int, String) -> Unit,
        private val onError: (String?) -> Unit
    ) : WebSocketClient(uri) {

        override fun onOpen(handshakedata: ServerHandshake?) {
            Log.d(TAG, "SockJS connection opened")
            onOpen()
        }

        override fun onMessage(message: String?) {
            message?.let { onMessage(it) }
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            Log.d(TAG, "SockJS connection closed: $code - $reason")
            onClose(code, reason ?: "Unknown")
        }

        override fun onError(ex: Exception?) {
            Log.e(TAG, "SockJS connection error", ex)
            onError(ex?.message)
        }
    }

    sealed class ConnectionState {
        object DISCONNECTED : ConnectionState()
        object CONNECTING : ConnectionState()
        object CONNECTED : ConnectionState()
        data class ERROR(val message: String) : ConnectionState()
    }
}
