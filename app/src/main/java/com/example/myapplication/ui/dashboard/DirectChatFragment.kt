package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.common.ProfileImageHelper

class DirectChatFragment: BaseFragment(R.layout.fragment_direct_chat) {

    private var prevSoftInputMode: Int? = null

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
        val peerAvatar = args?.getString("peerAvatarUrl")

        tvTitle?.text = peerName
        ProfileImageHelper.loadProfileImageIntoView(requireContext(), ivAvatar, peerAvatar)
    }

    override fun onResume() {
        super.onResume()
        activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val window = activity?.window
        if (window != null && prevSoftInputMode != null) {
            window.setSoftInputMode(prevSoftInputMode!!)
            prevSoftInputMode = null
        }
    }
}