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
import kotlinx.coroutines.launch

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
        // Do not connect globally here. We'll connect per-conversation when user opens a chat.

        // Observe connection state
        viewModelScope.launch {
            chatRepo.connectionState.collect { state ->
                _connectionState.postValue(state)
                Log.d("ChatViewModel", "Connection state: $state")
            }
        }
    }

    fun loadConversation(peerEmail: String, peerName: String, peerAvatar: String?) {
        viewModelScope.launch {
            // Ensure websocket is connected for this conversation (sender=my email, receiver=peerEmail)
            try {
                // Resolve email from DataStore then connect directly to WebSocket service
                val userData = UserDataManager.getInstance(getApplication())
                val myEmail = userData.getEmail()
                if (!myEmail.isNullOrBlank()) {
                    DirectChatWebSocketService
                        .getInstance(getApplication())
                        .connect(senderEmail = myEmail, receiverEmail = peerEmail)
                }
            } catch (e: Exception) {
                Log.w("ChatViewModel", "Failed to connect WS for peer: ${e.message}")
            }

            try {
                val conversation = chatRepo.getOrCreateConversation(peerEmail, peerName, peerAvatar)
                _currentConversation.postValue(conversation)
                conversationId = conversation.id

                // Load messages
                chatRepo.getMessagesForConversation(conversation.id).collect { messagesList ->
                    _messages.postValue(messagesList)
                }
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
                val result = chatRepo.sendMessage(
                    recipientEmail = conversation.peerEmail,
                    recipientName = conversation.peerName,
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
        viewModelScope.launch {
            chatRepo.sendTypingIndicator(conversation.peerEmail)
        }
    }

    fun markAsRead() {
        conversationId?.let { id ->
            viewModelScope.launch {
                chatRepo.markConversationAsRead(id)
            }
        }
    }

    /**
     * Restore conversation history received from websocket (or server) for a given peer.
     * Messages should be a list of DirectChatMessage objects (as received over WS).
     */
    fun restoreHistory(chatWith: String, messages: List<DirectChatMessage>) {
        viewModelScope.launch {
            try {
                val res = chatRepo.restoreConversationFromHistory(chatWith, messages)
                if (res.isSuccess) {
                    // Reload the conversation so UI observes persisted messages
                    loadConversation(chatWith, chatWith, null)
                } else {
                    Log.e("ChatViewModel", "Failed to restore history: ${res.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Exception while restoring history", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Don't disconnect WebSocket here as it's shared across the app
        // It will be managed by the Application lifecycle
    }
}
