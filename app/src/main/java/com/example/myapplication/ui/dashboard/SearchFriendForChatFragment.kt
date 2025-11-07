package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.BuildConfig
import com.example.myapplication.R
import com.example.myapplication.ui.common.BaseFragment

class SearchFriendForChatFragment: BaseFragment(R.layout.fragment_search_friends_for_chat) {

    private val vm: FriendsListViewModel by viewModels()
    private lateinit var adapter: FriendsListAdapter

    // Track previous soft input mode to restore later
    private var prevSoftInputMode: Int? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Override soft input behavior so bottom nav doesn't move with keyboard
        val window = activity?.window
        if (window != null && prevSoftInputMode == null) {
            prevSoftInputMode = window.attributes.softInputMode
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }

        val rv = view.findViewById<RecyclerView>(R.id.rv_friends)
        val progress = view.findViewById<ProgressBar>(R.id.progress)
        val addBtn = view.findViewById<ImageView>(R.id.btn_add)
        val illustration = view.findViewById<View>(R.id.illustration)

        adapter = FriendsListAdapter { friend ->
            // Normalize avatar URL - API returns relative path like "avatars/user/file.png"
            val avatarUrl = friend.avatarUrl?.trim()?.let { raw ->
                when {
                    raw.isBlank() -> null
                    raw.startsWith("http://", ignoreCase = true) ||
                    raw.startsWith("https://", ignoreCase = true) -> raw
                    // For relative paths, construct full API URL
                    else -> "${BuildConfig.BASE_URL.trimEnd('/')}/${raw.trimStart('/')}"
                }
            }

            runCatching {
                findNavController().navigate(R.id.action_searchFriendForChatFragment_to_directChatFragment, Bundle().apply {
                    putString("peerEmail", friend.email)
                    putString("peerName", "${friend.firstName} ${friend.lastName}".trim())
                    putString("peerAvatarUrl", avatarUrl)
                })
            }
        }
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        addBtn?.setOnClickListener {
            // Navigate to search friends screen to add new friends
            runCatching { findNavController().navigate(R.id.searchFriendsFragment) }
        }

        vm.friends.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            val hasData = list.isNotEmpty()
            rv.visibility = if (hasData) View.VISIBLE else View.GONE
            illustration?.visibility = if (hasData) View.GONE else View.VISIBLE
        }
        vm.loading.observe(viewLifecycleOwner) { isLoading ->
            progress.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
        vm.error.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrBlank()) {
                try { android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                vm.clearError()
            }
        }

        vm.loadFriends()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Restore previous soft input mode when leaving this screen
        val window = activity?.window
        if (window != null && prevSoftInputMode != null) {
            window.setSoftInputMode(prevSoftInputMode!!)
            prevSoftInputMode = null
        }
    }
}