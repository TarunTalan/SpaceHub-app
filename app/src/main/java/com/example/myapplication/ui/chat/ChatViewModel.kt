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
import com.example.myapplication.data.community.database.CommunityDatabase
import kotlinx.coroutines.launch

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val chatDao = CommunityDatabase.getInstance(app).chatDao()
    private val chatRepo = ChatRepository.getInstance(app, chatDao)

    private val _currentConversation = MutableLiveData<Conversation?>()
    @Suppress("unused")
    val currentConversation: LiveData<Conversation?> = _currentConversation

    private val _messages = MutableLiveData<List<ChatMessage>>(emptyList())
    val messages: LiveData<List<ChatMessage>> = _messages

    private val _connectionState = MutableLiveData<DirectChatWebSocketService.ConnectionState>()
    val connectionState: LiveData<DirectChatWebSocketService.ConnectionState> = _connectionState

    private val _sendingMessage = MutableLiveData<Boolean>(false)
    @Suppress("unused")
    val sendingMessage: LiveData<Boolean> = _sendingMessage

    private var conversationId: String? = null

    init {
        // Mirror repository connection state into LiveData
        viewModelScope.launch {
            try {
                chatRepo.connectionState.collect { state ->
                    _connectionState.postValue(state)
                }
            } catch (e: Exception) {
                Log.w("ChatViewModel", "Failed observing connection state", e)
            }
        }
    }

    fun loadConversation(peerEmail: String, peerName: String, peerAvatar: String?) {
        viewModelScope.launch {
            try {
                val conversation = chatRepo.getOrCreateConversation(peerEmail, peerName, peerAvatar)
                _currentConversation.postValue(conversation)
                conversationId = conversation.id

                // Connect the websocket for this sender/receiver pair (use stored repo method)
                val myEmail = com.example.myapplication.data.user.UserDataManager.getInstance(getApplication()).getEmail()
                if (!myEmail.isNullOrBlank()) {
                    val myEmailNonNull = myEmail
                    try {
                        chatRepo.connectWebSocket(myEmailNonNull, peerEmail)
                    } catch (e: Exception) {
                        Log.w("ChatViewModel", "Failed to connect websocket", e)
                    }
                }

                // Collect messages for this conversation and publish to LiveData
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
        viewModelScope.launch {
            try {
                val recipientEmail = conversation.peerEmail ?: return@launch
                chatRepo.sendTypingIndicator(recipientEmail)
            } catch (e: Exception) {
                // non-fatal
                Log.w("ChatViewModel", "Failed to send typing indicator", e)
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

    override fun onCleared() {
        // Intentionally do not disconnect WebSocket here: it is shared across the app
        super.onCleared()
    }
}
