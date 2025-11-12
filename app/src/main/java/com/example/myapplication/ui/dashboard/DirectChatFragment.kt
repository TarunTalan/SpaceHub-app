package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.ui.chat.ChatMessagesAdapter
import com.example.myapplication.ui.chat.ChatViewModel
import com.example.myapplication.ui.common.ProfileImageHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.view.ActionMode
import android.view.MenuItem
import android.widget.Toast

class DirectChatFragment: Fragment(R.layout.fragment_direct_chat) {

    private val chatViewModel: ChatViewModel by viewModels()
    private lateinit var messagesAdapter: ChatMessagesAdapter
    private var prevSoftInputMode: Int? = null
    private var prevImeBottom: Int = 0
    private var typingJob: Job? = null

    // Use an internal flag to manage selection UI instead of the system ActionMode
    private var selectionActive: Boolean = false

    // Helper to update selection toolbar title/count
    private fun updateSelectionTitle() {
        val count = messagesAdapter.getSelectedIds().size
        view?.findViewById<TextView>(R.id.selection_count)?.text = getString(R.string.selected_count, count)
    }

    @Suppress("DEPRECATION")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Back handler: if selection active, clear selection instead of navigating back
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectionActive || messagesAdapter.getSelectedIds().isNotEmpty()) {
                    finishSelection()
                } else {
                    // allow normal back
                    isEnabled = false
                    requireActivity().onBackPressed()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)

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
        val rootLayout = view.findViewById<View>(R.id.root_layout)

        // NEW: selection toolbar views
        val selectionToolbar = view.findViewById<View>(R.id.selection_toolbar)
        val selectionClose = view.findViewById<View>(R.id.selection_iv_close)
        val selectionDelete = view.findViewById<View>(R.id.selection_iv_delete)

        // Setup RecyclerView for chat messages
        // Pass true to hide avatar and sender name in direct chat
        messagesAdapter = ChatMessagesAdapter(hidePeerInfoInDirectChat = true)
        rvMessages?.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true // Start from bottom like WhatsApp
        }
        rvMessages?.adapter = messagesAdapter

        // Wire adapter callbacks for selection and click
        messagesAdapter.onItemLongClick = { _ ->
            // toggle selection already handled in adapter; ensure our selection UI is active
            ensureSelectionMode()
            updateSelectionTitle()
            // show custom selection toolbar and hide default title
            selectionToolbar?.visibility = View.VISIBLE
            view.findViewById<View>(R.id.constraintLayoutTitle)?.visibility = View.GONE
        }
        messagesAdapter.onItemClick = { _ ->
            // If selection active, update selection count UI
            if (selectionActive) {
                updateSelectionTitle()
            }
        }

        // selection toolbar button handlers
        selectionClose?.setOnClickListener {
            // clear selection and restore title toolbar
            finishSelection()
        }
        selectionDelete?.setOnClickListener {
            val ids = messagesAdapter.getSelectedIds().toList()
            if (ids.isNotEmpty()) {
                chatViewModel.deleteMessages(ids)
                Toast.makeText(requireContext(), "Deleted ${ids.size} message(s)", Toast.LENGTH_SHORT).show()
            }
            finishSelection()
        }

        // Use WindowInsets to move the chat bar up with keyboard and ensure the message list is padded
        val originalRootPaddingBottom = view.paddingBottom
        val originalRvBottom = rvMessages?.paddingBottom ?: 0

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            val imeBottom = imeInsets.bottom

            // Translate the chat bar up by the keyboard height (visual move)
            chatBar?.translationY = -imeBottom.toFloat()

            // Add bottom padding to the messages RecyclerView so its content is visible above the keyboard
            rvMessages?.setPadding(
                rvMessages.paddingLeft,
                rvMessages.paddingTop,
                rvMessages.paddingRight,
                originalRvBottom + imeBottom
            )

            // Keep root padding for system bars when IME is not shown, otherwise remove extra bottom padding
            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                if (imeBottom > 0) 0 else systemBarsInsets.bottom + originalRootPaddingBottom
            )

            // If IME just opened (transition from 0 to >0), scroll RecyclerView to last message
            if (this@DirectChatFragment.prevImeBottom == 0 && imeBottom > 0) {
                rvMessages?.post {
                    try {
                        val lastIndex = messagesAdapter.itemCount - 1
                        if (lastIndex >= 0) rvMessages.smoothScrollToPosition(lastIndex)
                    } catch (_: Exception) { }
                }
            }

            this@DirectChatFragment.prevImeBottom = imeBottom

            insets
        }

        chatBar?.bringToFront()
        ivBack?.setOnClickListener { runCatching { findNavController().navigateUp() } }

        val args = arguments
        val peerName = args?.getString("peerName").orEmpty().ifBlank { "Chat Room" }
        val peerEmail = args?.getString("peerEmail") ?: ""
        val peerAvatar = args?.getString("peerAvatarUrl")

        tvTitle?.text = peerName
        ProfileImageHelper.loadProfileImageIntoView(requireContext(), ivAvatar, peerAvatar)

        // Load conversation and messages
        if (peerEmail.isNotBlank()) {
            chatViewModel.loadConversation(peerEmail, peerName, peerAvatar)
        }

        // Observe connection state
        chatViewModel.connectionState.observe(viewLifecycleOwner) { _ ->
            // connection state observed by UI if needed; kept to allow future handling
        }

        // Observe messages - auto-scroll to bottom on new message
        chatViewModel.messages.observe(viewLifecycleOwner) { messages ->
            messagesAdapter.submitList(messages) {
                rvMessages?.post {
                    try {
                        val lastIndex = messagesAdapter.itemCount - 1
                        if (lastIndex >= 0) rvMessages.smoothScrollToPosition(lastIndex)
                    } catch (_: Exception) { }
                }
            }
            // If after update there are no selected ids, ensure action mode closed and restore title
            if (messagesAdapter.getSelectedIds().isEmpty()) {
                finishSelection()
            }
        }

        // Send message on send button click
        ivSend?.setOnClickListener {
            val text = etMessage?.text?.toString()?.trim()
            if (!text.isNullOrBlank()) {
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

        // Keep keyboard open when tapping the background: restore focus to the input and show IME.
        try {
            rootLayout?.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        try {
                            etMessage?.requestFocus()
                            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            // Show keyboard for the EditText; use SHOW_IMPLICIT to avoid toggling
                            imm.showSoftInput(etMessage, InputMethodManager.SHOW_IMPLICIT)
                        } catch (_: Exception) {}
                    }
                    MotionEvent.ACTION_UP -> {
                        try { v?.performClick() } catch (_: Exception) {}
                    }
                }
                // Return false so child views (buttons etc.) still receive touch events
                false
            }
        } catch (_: Exception) {}

        // Mark messages as read when visible
        chatViewModel.markAsRead()
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        chatViewModel.markAsRead()
    }

    @Suppress("DEPRECATION")
    override fun onDestroyView() {
        super.onDestroyView()
        typingJob?.cancel()
        val window = activity?.window
        if (window != null && prevSoftInputMode != null) {
            window.setSoftInputMode(prevSoftInputMode!!)
            prevSoftInputMode = null
        }
    }

    // Start selection mode using the custom selection toolbar (no system ActionMode)
    private fun ensureSelectionMode() {
        if (selectionActive) return
        selectionActive = true
        view?.findViewById<View>(R.id.selection_toolbar)?.visibility = View.VISIBLE
        view?.findViewById<View>(R.id.constraintLayoutTitle)?.visibility = View.GONE
        updateSelectionTitle()
    }

    private fun finishSelection() {
        messagesAdapter.clearSelection()
        selectionActive = false
        view?.findViewById<View>(R.id.selection_toolbar)?.visibility = View.GONE
        view?.findViewById<View>(R.id.constraintLayoutTitle)?.visibility = View.VISIBLE
        updateSelectionTitle()
    }
}