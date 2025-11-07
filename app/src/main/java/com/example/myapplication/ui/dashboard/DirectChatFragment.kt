package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.ui.chat.ChatMessagesAdapter
import com.example.myapplication.ui.chat.ChatViewModel
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.common.ProfileImageHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DirectChatFragment: BaseFragment(R.layout.fragment_direct_chat) {

    private val chatViewModel: ChatViewModel by viewModels()
    private lateinit var messagesAdapter: ChatMessagesAdapter
    private var prevSoftInputMode: Int? = null
    private var typingJob: Job? = null

    companion object {
        private const val TAG = "DirectChatFragment"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val window = activity?.window
        if (window != null && prevSoftInputMode == null) {
            prevSoftInputMode = window.attributes.softInputMode
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        val tvTitle = view.findViewById<TextView>(R.id.chatRoom)
        val ivAvatar = view.findViewById<ImageView>(R.id.iv_peer_avatar)
        val ivBack = view.findViewById<ImageView>(R.id.back_arrow)
        val chatBar = view.findViewById<View>(R.id.constraintLayoutChat)
        val rvMessages = view.findViewById<RecyclerView>(R.id.rv_messages)
        val etMessage = view.findViewById<EditText>(R.id.etChat)
        val ivSend = view.findViewById<ImageView>(R.id.iv_send)

        // Setup RecyclerView for chat messages
        messagesAdapter = ChatMessagesAdapter()
        rvMessages?.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true // Start from bottom like WhatsApp
        }
        rvMessages?.adapter = messagesAdapter

        // Use WindowInsets to move the chat bar up with keyboard
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Translate the chat bar up by the keyboard height
            chatBar?.translationY = -imeInsets.bottom.toFloat()

            // Also apply bottom padding to the root to keep content above system bars
            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                if (imeInsets.bottom > 0) 0 else systemBarsInsets.bottom
            )

            insets
        }

        chatBar?.bringToFront()
        ivBack?.setOnClickListener { runCatching { findNavController().navigateUp() } }

        val args = arguments
        val peerName = args?.getString("peerName").orEmpty().ifBlank { "Chat Room" }
        val peerEmail = args?.getString("peerEmail") ?: ""
        val peerAvatar = args?.getString("peerAvatarUrl")

        Log.d(TAG, "DirectChat opened with peer: $peerName ($peerEmail), avatar: $peerAvatar")

        tvTitle?.text = peerName
        ProfileImageHelper.loadProfileImageIntoView(requireContext(), ivAvatar, peerAvatar)

        // Load conversation and messages
        if (peerEmail.isNotBlank()) {
            Log.d(TAG, "Loading conversation with $peerEmail")
            chatViewModel.loadConversation(peerEmail, peerName, peerAvatar)
        } else {
            Log.e(TAG, "Peer email is blank!")
        }

        // Observe connection state
        chatViewModel.connectionState.observe(viewLifecycleOwner) { state ->
            Log.d(TAG, "WebSocket connection state: $state")
        }

        // Observe messages - auto-scroll to bottom on new message
        chatViewModel.messages.observe(viewLifecycleOwner) { messages ->
            Log.d(TAG, "Messages updated: ${messages.size} messages")
            messagesAdapter.submitList(messages) {
                rvMessages?.post {
                    if (messages.isNotEmpty()) {
                        rvMessages.scrollToPosition(messages.size - 1)
                    }
                }
            }
        }

        // Send message on send button click
        ivSend?.setOnClickListener {
            val text = etMessage?.text?.toString()?.trim()
            if (!text.isNullOrBlank()) {
                Log.d(TAG, "Sending message: $text")
                chatViewModel.sendMessage(text)
                etMessage.text?.clear()
            }
        }

        // Typing indicator (debounced)
        etMessage?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                typingJob?.cancel()
                if (!s.isNullOrBlank()) {
                    typingJob = lifecycleScope.launch {
                        delay(500)
                        chatViewModel.sendTypingIndicator()
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Mark messages as read when visible
        chatViewModel.markAsRead()
    }

    override fun onResume() {
        super.onResume()
        activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        chatViewModel.markAsRead()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        typingJob?.cancel()
        val window = activity?.window
        if (window != null && prevSoftInputMode != null) {
            window.setSoftInputMode(prevSoftInputMode!!)
            prevSoftInputMode = null
        }
    }
}