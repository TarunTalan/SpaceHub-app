package com.example.myapplication.data.chat.websocket

import android.content.Context
import android.util.Log
import com.example.myapplication.data.network.SharedPrefsTokenStore
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

class DirectChatWebSocketService private constructor(private val context: Context) {

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private val messageChannel = Channel<DirectChatMessage>(Channel.BUFFERED)
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.DISCONNECTED)

    val messages: Flow<DirectChatMessage> = messageChannel.receiveAsFlow()
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    companion object {
        @Volatile
        private var INSTANCE: DirectChatWebSocketService? = null

        fun getInstance(context: Context): DirectChatWebSocketService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DirectChatWebSocketService(context.applicationContext).also { INSTANCE = it }
            }
        }

        private const val TAG = "DirectChatWebSocket"
        private const val WS_URL = "wss://codewithketan.me/ws/direct-chat"
    }

    fun connect() {
        if (webSocket != null) {
            Log.d(TAG, "WebSocket already connected or connecting")
            return
        }

        val token = SharedPrefsTokenStore(context).getAccessToken()
        if (token.isNullOrEmpty()) {
            Log.e(TAG, "No auth token available")
            _connectionState.value = ConnectionState.ERROR("No auth token")
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        Log.d(TAG, "Connecting to WebSocket: $WS_URL")

        val request = Request.Builder()
            .url(WS_URL)
            .addHeader("Authorization", "Bearer $token")
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected successfully")
                _connectionState.value = ConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received message: $text")
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "Received bytes: ${bytes.hex()}")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code - $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code - $reason")
                _connectionState.value = ConnectionState.DISCONNECTED
                this@DirectChatWebSocketService.webSocket = null
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}", t)
                _connectionState.value = ConnectionState.ERROR(t.message ?: "Connection failed")
                this@DirectChatWebSocketService.webSocket = null
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val message = gson.fromJson(text, DirectChatMessage::class.java)
            messageChannel.trySend(message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: $text", e)
        }
    }

    fun sendMessage(senderEmail: String, receiverEmail: String, content: String): Boolean {
        val ws = webSocket
        if (ws == null || _connectionState.value != ConnectionState.CONNECTED) {
            Log.e(TAG, "Cannot send message - not connected")
            return false
        }

        try {
            val message = DirectChatMessageRequest(
                senderEmail = senderEmail,
                receiverEmail = receiverEmail,
                content = content
            )
            val json = gson.toJson(message)
            Log.d(TAG, "Sending message: $json")
            return ws.send(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            return false
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting WebSocket")
        webSocket?.close(1000, "User disconnected")
        webSocket = null
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
    val content: String
)

// Response format from server
data class DirectChatMessage(
    @SerializedName("id")
    val id: String?,

    @SerializedName("senderEmail")
    val senderEmail: String = "",

    @SerializedName("receiverEmail")
    val receiverEmail: String = "",

    @SerializedName("content")
    val content: String = "",

    @SerializedName("timestamp")
    val timestamp: String = ""
)

