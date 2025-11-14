package com.example.myapplication.ui.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.chat.model.ChatMessage
import com.example.myapplication.data.chat.model.Conversation
import com.example.myapplication.data.chat.repository.ChatRepository
import com.example.myapplication.data.chat.websocket.DirectChatWebSocketService
import com.example.myapplication.data.chat.websocket.DirectChatMessage
import com.example.myapplication.data.community.database.CommunityDatabase
import com.example.myapplication.data.user.UserDataManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val chatDao = CommunityDatabase.getInstance(app).chatDao()
    private val chatRepo = ChatRepository.getInstance(app, chatDao)

    private val _currentConversation = MutableLiveData<Conversation?>()
    val currentConversation: LiveData<Conversation?> = _currentConversation

    private val _messages = MutableLiveData<List<ChatMessage>>(emptyList())
    val messages: LiveData<List<ChatMessage>> = _messages

    private val _connectionState = MutableLiveData<DirectChatWebSocketService.ConnectionState>()
    val connectionState: LiveData<DirectChatWebSocketService.ConnectionState> = _connectionState

    private val _sendingMessage = MutableLiveData<Boolean>(false)
    val sendingMessage: LiveData<Boolean> = _sendingMessage

    private var conversationId: String? = null

    init {
        // Mirror repository connection state into LiveData
        viewModelScope.launch {
            try {
                chatRepo.connectionState.collect { state ->
                    _connectionState.postValue(state)
                }
            } catch (e: CancellationException) {
                // Expected when the ViewModel or scope is being cancelled; ignore quietly
            } catch (e: Exception) {
                Log.w("ChatViewModel", "Failed observing connection state", e)
            }
        }
    }

    fun loadConversation(peerEmail: String, peerName: String, peerAvatar: String?) {
        viewModelScope.launch {
            // Connection to websocket will be managed by the repository. We avoid connecting here
            // to prevent duplicate connection attempts which can cause duplicate events/histories.

            try {
                val conversation = chatRepo.getOrCreateConversation(peerEmail, peerName, peerAvatar)
                _currentConversation.postValue(conversation)
                conversationId = conversation.id

                // Connect the websocket for this sender/receiver pair (use stored repo method)
                val myEmail = UserDataManager.getInstance(getApplication()).getEmail()
                myEmail?.takeIf { it.isNotBlank() }?.let { email ->
                    try {
                        chatRepo.connectWebSocket(email, peerEmail)

                        // Wait briefly (3s) for the repository to process any server-sent history for this peer
                        // If historyProcessed emits the peer email, it means restoreConversationFromHistory finished.
                        withTimeoutOrNull(3000L) {
                            // Wait until repository emits that it processed history for this peer (case-insensitive)
                            chatRepo.historyProcessed.first { value -> value.equals(peerEmail, true) }
                        }
                    } catch (e: Exception) {
                        Log.w("ChatViewModel", "Failed to connect websocket or wait for history", e)
                    }
                }

                // Collect messages for this conversation and publish to LiveData
                chatRepo.getMessagesForConversation(conversation.id).collect { messagesList ->
                    // Dedupe for UI: collapse exact/near-duplicate messages that may come from echoes
                    fun normalize(s: String?) = s?.trim()?.replace(Regex("\\p{Punct}|\\s+"), " ")?.lowercase() ?: ""

                    val deduped = mutableListOf<ChatMessage>()
                    for (m in messagesList) {
                        val key = Triple(m.senderId, normalize(m.content), (m.timestamp / 2000L)) // bucket every 2s
                        val exists = deduped.any { existing ->
                            existing.senderId == key.first &&
                                    normalize(existing.content) == key.second &&
                                    (Math.abs(existing.timestamp - m.timestamp) < 2000L)
                        }
                        if (!exists) deduped.add(m)
                    }

                    _messages.postValue(deduped)
                }
            } catch (e: CancellationException) {
                // Normal: coroutine was cancelled because a new load or ViewModel cleared; ignore
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to load conversation", e)
            }
        }
    }

    fun sendMessage(content: String) {
        val conversation = _currentConversation.value ?: return
        if (content.isBlank()) return

        viewModelScope.launch {
            _sendingMessage.postValue(true)
            try {
                val recipientEmail = conversation.peerEmail ?: return@launch
                val recipientName = conversation.peerName ?: recipientEmail
                val result = chatRepo.sendMessage(
                    recipientEmail = recipientEmail,
                    recipientName = recipientName,
                    content = content.trim()
                )

                if (result.isFailure) {
                    Log.e("ChatViewModel", "Failed to send message", result.exceptionOrNull())
                }
            } finally {
                _sendingMessage.postValue(false)
            }
        }
    }

    fun sendTypingIndicator() {
        val conversation = _currentConversation.value ?: return
        // Only call sendTypingIndicator if peerEmail is non-null
        conversation.peerEmail?.let { email ->
            viewModelScope.launch {
                chatRepo.sendTypingIndicator(email)
            }
        }
    }

    fun markAsRead() {
        conversationId?.let { id ->
            viewModelScope.launch {
                try {
                    chatRepo.markConversationAsRead(id)
                } catch (e: Exception) {
                    Log.w("ChatViewModel", "Failed to mark conversation read", e)
                }
            }
        }
    }

    /**
     * Restore conversation history received from websocket (or server) for a given peer.
     * Messages should be a list of DirectChatMessage objects (as received over WS).
     */
    @Suppress("unused")
    fun restoreHistory(chatWith: String, messages: List<DirectChatMessage>) {
        viewModelScope.launch {
            try {
                // Persist history via public repository helper (non-blocking) and then reload the conversation
                chatRepo.loadHistoryFromPayload(chatWith, messages)
                loadConversation(chatWith, chatWith, null)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Exception while restoring history", e)
            }
        }
    }

    /**
     * Request deletion of message(s) by id. Delegates to repository which will send WS delete and update local DB.
     */
    fun deleteMessages(messageIds: List<String>) {
        viewModelScope.launch {
            try {
                chatRepo.deleteMessages(messageIds)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to delete messages", e)
            }
        }
    }

    /**
     * Convenience: when entering a chat where the server provided a history payload, call this.
     * It will persist the payload into the DB (via repository) and then load the conversation.
     */
    @Suppress("unused")
    fun loadConversationWithHistory(peerEmail: String, peerName: String?, peerAvatar: String?, history: List<DirectChatMessage>) {
        viewModelScope.launch {
            try {
                // Persist history into the DB using repository helper then load the conversation UI
                chatRepo.loadHistoryFromPayload(peerEmail, history)
            } catch (e: Exception) {
                Log.w("ChatViewModel", "Failed to persist history payload: ${'$'}{e.message}")
            }

            // After persisting, continue with normal conversation load which will observe messages from DB
            loadConversation(peerEmail, peerName ?: peerEmail, peerAvatar)
        }
    }

    override fun onCleared() {
        super.onCleared()

    }
}
