package com.example.myapplication.ui.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.chat.model.ChatMessage
import com.example.myapplication.data.chat.model.MessageStatus
import com.example.myapplication.data.chat.websocket.ChatWebSocket
import com.example.myapplication.data.user.UserDataManager
import com.example.myapplication.data.network.SharedPrefsTokenStore
import com.example.myapplication.data.community.database.CommunityDatabase
import com.example.myapplication.data.chat.repository.ChatRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.UUID

class ChatRoomViewModel(app: Application) : AndroidViewModel(app) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val messagesMutex = Mutex()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var webSocket: ChatWebSocket? = null

    private val baseWss = "wss://codewithketan.me/chat"

    fun connectToRoom(roomCode: String) {
        viewModelScope.launch {
            try {
                val email = UserDataManager.getInstance(getApplication()).getEmail()
                if (email.isNullOrBlank()) {
                    Log.w("ChatRoomVM", "No email set; cannot connect")
                    return@launch
                }
                _loading.value = true
                val token = SharedPrefsTokenStore(getApplication()).getAccessToken()
                webSocket = ChatWebSocket(baseWss, token)
                webSocket?.connect(roomCode, email)

                // collect incoming messages on IO
                launch(Dispatchers.IO) {
                    try {
                        webSocket?.incomingMessages?.collect { incomingMsg ->
                            // Log incoming raw mapped message for diagnostics
                            try { android.util.Log.d("ChatRoomVM", "INCOMING MSG: $incomingMsg") } catch (_: Exception) {}
                            // Robust de-duplication and update logic
                            viewModelScope.launch {
                                try {
                                    messagesMutex.withLock {
                                        val next = ArrayList(_messages.value)

                                        // Make a mutable processed copy we can adjust before matching
                                        var processed = incomingMsg

                                        // If processed has JSON content, try to extract inner senderName
                                        try {
                                            val contentTrim = processed.content.trim()
                                            if ((contentTrim.startsWith("{") || contentTrim.startsWith("["))) {
                                                val inner = com.google.gson.JsonParser.parseString(contentTrim)
                                                if (inner.isJsonObject) {
                                                    val obj = inner.asJsonObject
                                                    if ((processed.senderName == null || processed.senderName.isBlank()) && obj.has("senderName") && obj.get("senderName").isJsonPrimitive) {
                                                        val innerSenderName = obj.get("senderName").asString
                                                        if (!innerSenderName.isNullOrBlank()) processed = processed.copy(senderName = innerSenderName)
                                                    }
                                                    // If inner contains its own 'content', prefer that as display content
                                                    if (obj.has("content") && obj.get("content").isJsonPrimitive) {
                                                        val innerContent = obj.get("content").asString
                                                        if (!innerContent.isNullOrBlank()) processed = processed.copy(content = innerContent)
                                                    }
                                                }
                                            }
                                        } catch (_: Exception) {}

                                        // If this is a join message like "email joined the chat", try to replace email with known username
                                        try {
                                            val joinRegex = Regex("([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}) joined the chat", RegexOption.IGNORE_CASE)
                                            val m = joinRegex.find(processed.content)
                                            if (m != null) {
                                                val emailJoined = m.groupValues[1]
                                                val knownName = next.firstOrNull { it.senderId.equals(emailJoined, ignoreCase = true) && !it.senderName.isNullOrBlank() }?.senderName
                                                if (!knownName.isNullOrBlank()) {
                                                    processed = processed.copy(content = processed.content.replace(emailJoined, knownName))
                                                  }
                                            }
                                        } catch (_: Exception) {}

                                        // Try clientId match first (use processed content which may be inner JSON)
                                        val clientId = extractClientIdFromContent(processed.content)
                                        if (!clientId.isNullOrBlank()) {
                                            val idxByClientId = next.indexOfFirst { it.id == clientId }
                                            if (idxByClientId >= 0) {
                                                // Replace optimistic message with server-provided one (preserve order)
                                                next[idxByClientId] = processed
                                                _messages.value = next
                                                try { android.util.Log.d("ChatRoomVM", "Replaced optimistic message by clientId=$clientId at idx=$idxByClientId") } catch (_: Exception) {}
                                                return@withLock
                                            }
                                        }

                                        // 1) Try exact id match (server id)
                                        val incomingId = processed.id
                                        if (!incomingId.isNullOrBlank()) {
                                            val idxById = next.indexOfFirst { it.id == incomingId }
                                            if (idxById >= 0) {
                                                next[idxById] = processed
                                                _messages.value = next
                                                return@withLock
                                            }
                                        }

                                        // 2) If the incoming message is from me, try to match an optimistic message I sent
                                        val myEmail = UserDataManager.getInstance(getApplication()).getEmail()?.lowercase()
                                        val incomingSender = processed.senderId?.lowercase()
                                        if (!incomingSender.isNullOrBlank() && !myEmail.isNullOrBlank() && incomingSender == myEmail) {
                                            // find last optimistic message from me with same content
                                            val idxOptimistic = next.indexOfLast { it.isFromMe && it.content == processed.content }
                                            if (idxOptimistic >= 0) {
                                                // Replace the optimistic entry with the server message
                                                next[idxOptimistic] = processed
                                                _messages.value = next
                                                return@withLock
                                            }
                                        }

                                        // 3) Prefer matching optimistic (SENDING) messages first — these came from this client
                                        val idxSending = next.indexOfLast { existing ->
                                            existing.status == MessageStatus.SENDING &&
                                            existing.isFromMe == processed.isFromMe &&
                                            existing.content == processed.content
                                        }
                                        if (idxSending >= 0) {
                                            next[idxSending] = processed
                                            try { android.util.Log.d("ChatRoomVM", "Replaced SENDING optimistic at idx=$idxSending") } catch (_: Exception) {}
                                        } else {
                                            // Fallback: match by content + nearby timestamp window (10s)
                                            val matchedIdx = next.indexOfFirst { existing ->
                                                existing.isFromMe == processed.isFromMe &&
                                                existing.content == processed.content &&
                                                Math.abs(existing.timestamp - processed.timestamp) < 10_000
                                            }
                                            if (matchedIdx >= 0) {
                                                next[matchedIdx] = processed
                                                try { android.util.Log.d("ChatRoomVM", "Replaced message at idx=$matchedIdx by content+timestamp match") } catch (_: Exception) {}
                                            } else {
                                                next.add(processed)
                                                try { android.util.Log.d("ChatRoomVM", "Appended incoming message (no match found)") } catch (_: Exception) {}
                                            }
                                        }
                                        _messages.value = next
                                    }
                                    try { android.util.Log.d("ChatRoomVM", "MESSAGES UPDATED size=${_messages.value.size} last=${_messages.value.lastOrNull()} ") } catch (_: Exception) {}
                                } catch (t: Throwable) {
                                    try { android.util.Log.d("ChatRoomVM", "MESSAGES UPDATE FAILED: ${t.message}") } catch (_: Exception) {}
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e("ChatRoomVM", "collect incoming failed: ${t.message}")
                    } finally {
                        _loading.value = false
                    }
                }
            } catch (t: Throwable) {
                Log.e("ChatRoomVM", "connectToRoom error: ${t.message}")
                _loading.value = false
            }
        }
    }

    fun sendMessage(text: String, chatRoomCode: String) {
        viewModelScope.launch {
            try {
                val userData = UserDataManager.getInstance(getApplication())
                val email = userData.getEmail() ?: return@launch
                val username = userData.usernameFlow.first() ?: email
                // Build ChatMessage using Room entity fields
                val msg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    conversationId = chatRoomCode,
                    senderId = email,
                    senderName = username,
                    senderAvatar = null,
                    recipientId = null,
                    content = text,
                    timestamp = System.currentTimeMillis(),
                    status = MessageStatus.SENDING,
                    isFromMe = true
                )
                webSocket?.sendMessage(msg)
                // optimistic UI append
                val next = ArrayList(_messages.value)
                messagesMutex.withLock {
                    next.add(msg)
                    _messages.value = next
                }
            } catch (t: Throwable) {
                Log.e("ChatRoomVM", "sendMessage error: ${t.message}", t)
            }
        }
    }

    fun disconnect() {
        try { webSocket?.close() } catch (_: Exception) {}
    }

    /**
     * Request deletion of message(s) by id for chat rooms.
     * Delegates to ChatRepository which sends WS delete and updates local DB.
     */
    fun deleteMessages(messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        viewModelScope.launch {
            try {
                // Use repository to perform delete (it will send ws frame and update DB)
                val chatDao = CommunityDatabase.getInstance(getApplication()).chatDao()
                val repo = ChatRepository.getInstance(getApplication(), chatDao)
                val res = repo.deleteMessages(messageIds)
                if (res.isFailure) {
                    Log.w("ChatRoomVM", "deleteMessages repo failed: ${'$'}{res.exceptionOrNull()?.message}")
                }
            } catch (t: Throwable) {
                Log.e("ChatRoomVM", "deleteMessages error: ${'$'}{t.message}", t)
            }
        }
    }

    // Helper: if incoming.content is a JSON blob that contains an original client id,
    // extract it and return the id (or null).
    private fun extractClientIdFromContent(content: String?): String? {
        if (content.isNullOrBlank()) return null
        val trimmed = content.trim()
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return null
        return try {
            val el = com.google.gson.JsonParser.parseString(trimmed)
            if (el.isJsonObject) {
                val obj = el.asJsonObject
                if (obj.has("id") && obj.get("id").isJsonPrimitive) obj.get("id").asString else null
            } else null
        } catch (_: Exception) { null }
    }
}
