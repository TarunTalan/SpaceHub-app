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
import java.lang.ref.WeakReference

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
     * Connect the Direct WebSocket for 1:1 chat. The server expects senderEmail and receiverEmail
     * to be present as query parameters in the connection URL. Pass both values (non-empty).
     */
    fun connect(senderEmail: String, receiverEmail: String) {
        if (webSocket != null) return

        val token = SharedPrefsTokenStore(context).getAccessToken()
        if (token.isNullOrEmpty()) {
            _connectionState.value = ConnectionState.ERROR("No auth token")
            return
        }

        // Build URL with required query params
        val encodedSender = try { java.net.URLEncoder.encode(senderEmail, "UTF-8") } catch (_: Exception) { senderEmail }
        val encodedReceiver = try { java.net.URLEncoder.encode(receiverEmail, "UTF-8") } catch (_: Exception) { receiverEmail }
        val urlWithParams = "$WS_URL?senderEmail=$encodedSender&receiverEmail=$encodedReceiver"

        _connectionState.value = ConnectionState.CONNECTING

        val request = Request.Builder()
            .url(urlWithParams)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Sec-WebSocket-Protocol", "v10.stomp")
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // binary frames are not used in current protocol
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
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
            Log.e(TAG, "Failed to parse WS message", e)
        }
    }

    fun sendMessage(senderEmail: String, receiverEmail: String, content: String, messageId: String): Boolean {
        val ws = webSocket
        if (ws == null || _connectionState.value != ConnectionState.CONNECTED) {
            Log.e(TAG, "Cannot send message - not connected")
            return false
        }

        try {
            val message = DirectChatMessageRequest(
                senderEmail = senderEmail,
                receiverEmail = receiverEmail,
                content = content,
                messageId = messageId
            )
            val json = gson.toJson(message)
            val sentResult = ws.send(json)
            return sentResult
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send WS message", e)
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
    val content: String,

    @SerializedName("messageId")
    val messageId: String
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
    val timestamp: String = "",

    @SerializedName("type")
    val type: String? = null,

    @SerializedName("messageId")
    val messageId: String? = null
)
