package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.findNavController
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.myapplication.BuildConfig
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.R
import com.example.myapplication.ui.chat.ChatRoomViewModel
import com.example.myapplication.ui.chat.ChatMessagesAdapter
import kotlinx.coroutines.launch

class ChatRoomFragment: BaseFragment(R.layout.fragment_chat_room) {
    private val vm: ChatRoomViewModel by viewModels()
    private var keyboardListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var prevSoftInputMode: Int? = null
    private var prevImeBottom: Int = 0
    private var scrolledOnImeOpen: Boolean = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Read nav args and show room details in header
        val roomCode = arguments?.getString("chatRoomCode")
        val roomName = arguments?.getString("chatRoomName")
        val communityImageUrl = arguments?.getString("communityImageUrl")
        val titleView = view.findViewById<TextView>(R.id.chatRoom)
        if (!roomName.isNullOrBlank()) {
            titleView?.text = roomName
        } else if (!roomCode.isNullOrBlank()) {
            titleView?.text = roomCode
        }

        // Load community / group image into header if provided
        try {
            val imgView = view.findViewById<ImageView>(R.id.chat_community_image)
            imgView?.let { iv ->
                val raw = communityImageUrl?.trim()
                val avatarUrl = raw?.takeIf { it.isNotBlank() }?.let { r ->
                    when {
                        r.startsWith("http://", true) || r.startsWith("https://", true) -> r
                        else -> "${BuildConfig.BASE_URL.trimEnd('/')}/${r.trimStart('/')}"
                    }
                }

                Glide.with(iv.context)
                    .load(avatarUrl ?: R.drawable.default_profile)
                    .placeholder(R.drawable.default_profile)
                    .error(R.drawable.default_profile)
                    .circleCrop()
                    .into(iv)
            }
        } catch (_: Exception) {}

        // Back arrow behavior: navigate up
        view.findViewById<ImageView>(R.id.back_arrow)?.setOnClickListener {
            try { vm.disconnect(); findNavController().navigateUp() } catch (_: Exception) {}
        }

        // Messages list (use RecyclerView from layout)
        val messagesRv = view.findViewById<RecyclerView>(R.id.rv_messages)!!
        val adapter = ChatMessagesAdapter()
        messagesRv.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        messagesRv.adapter = adapter

        // Ensure the window resizes when IME (keyboard) appears so RecyclerView is visible
        try {
            val window = activity?.window
            if (window != null && prevSoftInputMode == null) {
                prevSoftInputMode = window.attributes.softInputMode
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        } catch (_: Exception) {}

        // Use WindowInsets to react to IME reliably (same as DirectChatFragment)
        try {
            val root = view.findViewById<View>(R.id.root_layout)
            val chatBar = view.findViewById<View>(R.id.constraintLayoutChat)
            val originalRootPaddingBottom = root?.paddingBottom ?: 0
            val originalRvBottom = messagesRv.paddingBottom

            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
                val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

                val imeBottom = imeInsets.bottom

                // Translate the chat bar up by IME height (visual move)
                chatBar?.translationY = -imeBottom.toFloat()

                // Add bottom padding to the messages RecyclerView so its content is visible above the keyboard
                messagesRv.setPadding(
                    messagesRv.paddingLeft,
                    messagesRv.paddingTop,
                    messagesRv.paddingRight,
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
                if (this@ChatRoomFragment.prevImeBottom == 0 && imeBottom > 0) {
                    messagesRv.post {
                        try {
                            safeScrollToBottom(messagesRv)
                            scrolledOnImeOpen = true
                            // clear the flag shortly after to allow subsequent normal scrolling
                            messagesRv.postDelayed({ scrolledOnImeOpen = false }, 300)
                        } catch (_: Exception) {}
                    }
                }

                this@ChatRoomFragment.prevImeBottom = imeBottom

                insets
            }

            chatBar?.bringToFront()
            ViewCompat.requestApplyInsets(root)
        } catch (_: Exception) {}

        // Connect to websocket
        val roomCodeArg = arguments?.getString("chatRoomCode")
        if (!roomCodeArg.isNullOrBlank()) {
            vm.connectToRoom(roomCodeArg)
        }

        // Collect messages and submit to adapter
        viewLifecycleOwner.lifecycleScope.launch {
            vm.messages.collect { list ->
                try { android.util.Log.d("ChatRoomFragment", "ADAPTER RECEIVING listSize=${list.size} last=${list.lastOrNull()}") } catch (_: Exception) {}
                // scroll only after ListAdapter applied diffs to avoid jumps
                adapter.submitList(list) {
                    messagesRv.post {
                        try {
                            if (!scrolledOnImeOpen) {
                                safeScrollToBottom(messagesRv)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        // Send button and input
        val sendBtn = view.findViewById<ImageView>(R.id.iv_send)
        val input = view.findViewById<EditText>(R.id.etChat)
        sendBtn?.setOnClickListener {
            val text = input?.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank() && !roomCodeArg.isNullOrBlank()) {
                vm.sendMessage(text, roomCodeArg)
                input?.setText("")
                // let adapter/appended optimistic update take effect, then smoothly scroll to bottom (unless IME just triggered a scroll)
                try { messagesRv.post {
                    if (!scrolledOnImeOpen) safeScrollToBottom(messagesRv)
                 } } catch (_: Exception) {}
              }
          }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { vm.disconnect() } catch (_: Exception) {}
        // Remove keyboard listener to avoid leaks
        try {
            val root = requireView().findViewById<View>(R.id.root_layout)
            keyboardListener?.let { l ->
                try {
                    root.viewTreeObserver.removeOnGlobalLayoutListener(l)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        // restore window softInputMode
        try {
            val window = activity?.window
            if (window != null && prevSoftInputMode != null) {
                window.setSoftInputMode(prevSoftInputMode!!)
                prevSoftInputMode = null
            }
        } catch (_: Exception) {}
    }

    // Helper: safely scroll to the last adapter position if valid. Guards against invalid target positions.
    private fun safeScrollToBottom(rv: RecyclerView) {
        try {
            val count = rv.adapter?.itemCount ?: 0
            val target = count - 1
            if (target >= 0 && target < count) {
                // Only call smoothScroll if the RecyclerView is attached to window
                if (rv.isAttachedToWindow && isAdded) rv.smoothScrollToPosition(target)
            }
        } catch (_: Exception) {}
    }
}