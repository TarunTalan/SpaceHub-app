package com.example.myapplication.ui.community

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.example.myapplication.data.community.model.DataRoom
import com.example.myapplication.data.community.repository.CommunityRepository
import com.example.myapplication.data.user.UserDataManager
import com.example.myapplication.ui.community.adapter.RoomAdapter
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch

class RoomFragment : Fragment(R.layout.fragment_room) {

    private val roomViewModel: RoomViewModel by viewModels()

    private val chatRooms = mutableListOf<DataRoom>()
    private lateinit var chatRoomsAdapter: RoomAdapter
    private var chatRoomsExpanded = true
    // keep args as properties so dialog can access them
    private var currentCommunityId: String? = null
    private var currentRoomId: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get arguments
        val communityId = arguments?.getString("communityId")
        val roomId = arguments?.getString("roomId")
        // server-provided roomCode (preferred) — may be null for older data
        val roomCodeArg = arguments?.getString("roomCode")
        currentCommunityId = communityId
        currentRoomId = roomCodeArg ?: roomId
        val roomName = arguments?.getString("roomName")
        val communityName = arguments?.getString("communityName")
        val communityImageUrl = arguments?.getString("communityImageUrl")
        val memberCount = arguments?.getInt("memberCount", 0) ?: 0
        val adminCount = arguments?.getInt("adminCount", 0) ?: 0

        if (communityId.isNullOrBlank() || roomId.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Missing room information", Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
            return
        }

        // Resolve parent room code early: prefer server-provided roomCode when available.
        // At this point we already verified communityId and (some form of) roomId are non-null.
        roomViewModel.startResolve(communityId, currentRoomId ?: roomId)
        viewLifecycleOwner.lifecycleScope.launch {
            roomViewModel.resolvedRoomCode.collect { code ->
                if (!code.isNullOrBlank()) {
                    currentRoomId = code
                }
            }
        }

        // Initialize views
        val backButton: ImageView? = view.findViewById(R.id.imageView)
        val tvUsername: TextView? = view.findViewById(R.id.tvUsername)
        val settingsButton = view.findViewById<ImageView>(R.id.setting_community)
        val communityImage = view.findViewById<ImageView>(R.id.community_image)
        val communityNameTv = view.findViewById<TextView>(R.id.community_name)
        val memberCountTv = view.findViewById<TextView>(R.id.member_count_tv)
        val adminCountTv = view.findViewById<TextView>(R.id.admin_count_tv)
        val tvRoomsName = view.findViewById<TextView>(R.id.tv_rooms_name)
        val rvChatRooms = view.findViewById<RecyclerView>(R.id.rv_chat_rooms)
        val swipeRefresh = view.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)
        val fabCreateRoom = view.findViewById<FloatingActionButton>(R.id.fab_create_room)

        // Set back button
        backButton?.setOnClickListener {
            findNavController().navigateUp()
        }

        // Set room name as title with # prefix
        val displayRoomName = if (roomName.isNullOrBlank()) {
            "#room"
        } else {
            if (roomName.startsWith("#")) roomName else "#$roomName"
        }
        // Set the header to show the room name (tv_rooms_name is actually used as section header)
        tvRoomsName?.text = "$displayRoomName - Chat Rooms"

        // Set community name
        communityNameTv?.text = communityName ?: "Community"

        // Set member and admin counts
        memberCountTv?.text = memberCount.toString()
        adminCountTv?.text = adminCount.toString()

        // Load community image
        if (!communityImageUrl.isNullOrBlank()) {
            Glide.with(requireContext())
                .load(communityImageUrl)
                .placeholder(R.drawable.default_comm_icon)
                .error(R.drawable.default_comm_icon)
                .circleCrop()
                .into(communityImage)
        }

        // Load username
        viewLifecycleOwner.lifecycleScope.launch {
            UserDataManager.getInstance(requireContext()).usernameFlow.collect { username ->
                tvUsername?.text = username ?: "User"
            }
        }

        // Setup chat rooms RecyclerView with adapter
        chatRoomsAdapter = RoomAdapter(
            onClick = { chatRoom ->
                try {
                    val code = chatRoom.roomCode.ifBlank { chatRoom.id }
                    val args = Bundle().apply {
                        putString("chatRoomCode", code)
                        putString("chatRoomName", chatRoom.name)
                        putString("communityImageUrl", communityImageUrl)
                    }
                    findNavController().navigate(R.id.chatRoomFragment, args)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Failed to open chat: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            },
            onLongClick = { chatRoom ->
                // Show delete dialog using AppDialogHelper
                com.example.myapplication.ui.common.AppDialogHelper.showConfirmation(requireContext(), "Delete Chat Room", "Are you sure you want to delete '${chatRoom.name}'?", positiveText = "Delete", negativeText = "Cancel", onPositive = {
                    deleteChatRoom(chatRoom)
                })
            }
        )

        rvChatRooms?.layoutManager = LinearLayoutManager(requireContext())
        rvChatRooms?.adapter = chatRoomsAdapter

        // Update UI based on chat rooms
        updateChatRoomsUI()

        // Setup expand/collapse toggle
        val ivToggle = view.findViewById<ImageView>(R.id.iv_toggle_your_comm)
        ivToggle?.setOnClickListener {
            chatRoomsExpanded = !chatRoomsExpanded
            applyChatRoomsToggleState(ivToggle)
        }

        // Show FAB for creating chat rooms only for community admins/owners/moderators.
        // Start hidden and reveal after checking role.
        fabCreateRoom?.visibility = View.GONE
        fabCreateRoom?.setOnClickListener {
            showCreateChatRoomDialog()
        }

        // Check community role and reveal FAB for privileged users
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val repo = CommunityRepository.getInstance(requireContext())
                var community = repo.getCommunityById(communityId)
                // If we don't have local info or role flags aren't set, try refreshing members once
                if (community == null || (community.isOwner == false && community.isModerator == false && community.role.isNullOrBlank())) {
                    // Attempt to refresh members/roles (network) to get up-to-date role flags
                    try { repo.fetchMembers(communityId, force = true) } catch (_: Exception) {}
                    community = repo.getCommunityById(communityId)
                }

                val shouldShowFab = try {
                    when {
                        community == null -> false
                        community.isOwner -> true
                        community.isModerator -> true
                        !community.role.isNullOrBlank() && community.role.trim().uppercase().contains("ADMIN") -> true
                        else -> false
                    }
                } catch (_: Exception) { false }

                if (shouldShowFab) {
                    fabCreateRoom?.visibility = View.VISIBLE
                } else {
                    fabCreateRoom?.visibility = View.GONE
                }
            } catch (_: Exception) {
                // leave hidden on error
                fabCreateRoom?.visibility = View.GONE
            }
        }

        // Hide settings button (not applicable for room view)
        settingsButton?.visibility = View.GONE

        // Load existing chat rooms for this parent room. Prefer `roomCode` if present.
        roomViewModel.loadChatRoomsForCommunity(communityId, currentRoomId ?: roomId)
        viewLifecycleOwner.lifecycleScope.launch {
            roomViewModel.chatRooms.collect { chatRoomsList ->
                chatRooms.clear()
                // Map DataChatRoom (flat) into DataRoom for adapter compatibility
                chatRooms.addAll(chatRoomsList.map { dataChatRoom ->
                    DataRoom(id = dataChatRoom.chatRoomCode, name = dataChatRoom.name, roomCode = dataChatRoom.chatRoomCode)
                })
                updateChatRoomsUI()
            }
        }

        // If server later resolves a different roomCode, reload using it
        viewLifecycleOwner.lifecycleScope.launch {
            roomViewModel.resolvedRoomCode.collect { resolvedCode ->
                if (!resolvedCode.isNullOrBlank() && resolvedCode != currentRoomId) {
                    currentRoomId = resolvedCode
                    roomViewModel.loadChatRoomsForCommunity(communityId, resolvedCode)
                }
            }
        }

        // Swipe refresh
        swipeRefresh?.setOnRefreshListener {
            // Refresh room data
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val repo = CommunityRepository.getInstance(requireContext())
                    val community = repo.getCommunityById(communityId)

                    // Update community info
                    community?.let {
                        communityNameTv?.text = it.name
                        memberCountTv?.text = it.memberCount.toString()

                        if (!it.profilePicUrl.isNullOrBlank()) {
                            Glide.with(requireContext())
                                .load(it.profilePicUrl)
                                .placeholder(R.drawable.default_comm_icon)
                                .error(R.drawable.default_comm_icon)
                                .circleCrop()
                                .into(communityImage)
                        }
                    }

                    // Reload chat rooms
                    loadChatRooms()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Failed to refresh: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    private fun loadChatRooms() {
        // Deprecated - kept for compatibility but we now use RoomViewModel.loadChatRoomsForCommunity
    }

    private fun showCreateChatRoomDialog() {
        val inflater = layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_create_chat_room, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_room_name)
        val tvError = dialogView.findViewById<TextView>(R.id.dialog_error)
        val btnCreate = dialogView.findViewById<android.widget.Button>(R.id.btn_create)
        val btnCancel = dialogView.findViewById<android.widget.Button>(R.id.btn_cancel)

        val dialog = try {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setView(dialogView)
                .create()
        } catch (_: Exception) {
            // Fallback to central createViewDialog which applies theme safely
            com.example.myapplication.ui.common.AppDialogHelper.createViewDialog(requireContext(), title = null, customView = dialogView, positiveText = null, negativeText = null, cancelable = true)
        }

        fun setLoading(loading: Boolean) {
            btnCreate.isEnabled = !loading
            btnCancel.isEnabled = !loading
            etName.isEnabled = !loading
        }

        btnCreate.setOnClickListener {
            val name = etName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                tvError.text = "Name is required"
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            tvError.visibility = View.GONE
            setLoading(true)
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val repo = CommunityRepository.getInstance(requireContext())
                    val res = repo.createChatRoom(currentCommunityId ?: return@launch, currentRoomId ?: return@launch, name)
                    val created = res.getOrNull()
                    if (created != null) {
                        val effectiveId = created.chatRoomCode.ifBlank { created.id }
                        val newRoom = DataRoom(id = effectiveId, name = created.name.ifBlank { name }, roomCode = created.chatRoomCode)
                        chatRooms.add(0, newRoom)
                        updateChatRoomsUI()
                        Toast.makeText(requireContext(), "Chat room '${created.name}' created", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } else {
                        val err = res.exceptionOrNull()
                        com.google.android.material.snackbar.Snackbar.make(requireView(), err?.message ?: "Failed to create chat room", com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
                            .setAction("Retry") { btnCreate.performClick() }
                            .show()
                    }
                } catch (e: Exception) {
                    com.google.android.material.snackbar.Snackbar.make(requireView(), "Failed: ${e.message}", com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
                } finally {
                    setLoading(false)
                }
            }
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }


    private fun deleteChatRoom(chatRoom: DataRoom) {
        // TODO: Add API endpoint for deleting chat rooms
        // For now, only remove locally
        chatRooms.remove(chatRoom)
        updateChatRoomsUI()

        Toast.makeText(requireContext(), "Chat room '${chatRoom.name}' deleted (local only)", Toast.LENGTH_SHORT).show()

        // When API is available:
        // viewLifecycleOwner.lifecycleScope.launch {
        //     val repo = CommunityRepository.getInstance(requireContext())
        //     repo.deleteChatRoom(chatRoom.id).onSuccess {
        //         chatRooms.remove(chatRoom)
        //         updateChatRoomsUI()
        //         Toast.makeText(requireContext(), "Chat room deleted", Toast.LENGTH_SHORT).show()
        //     }
        // }
    }

    private fun updateChatRoomsUI() {
        val rvChatRooms = view?.findViewById<RecyclerView>(R.id.rv_chat_rooms)
        val emptyRoomsView = view?.findViewById<View>(R.id.empty_rooms_view)

        if (chatRooms.isEmpty()) {
            emptyRoomsView?.visibility = View.VISIBLE
            rvChatRooms?.visibility = View.GONE
        } else {
            emptyRoomsView?.visibility = View.GONE
            rvChatRooms?.visibility = if (chatRoomsExpanded) View.VISIBLE else View.GONE
            chatRoomsAdapter.submitList(chatRooms.toList())
        }
    }

    private fun applyChatRoomsToggleState(ivToggle: ImageView) {
        val rvChatRooms = view?.findViewById<RecyclerView>(R.id.rv_chat_rooms)

        // Update visibility
        rvChatRooms?.visibility = if (chatRoomsExpanded && chatRooms.isNotEmpty()) View.VISIBLE else View.GONE

        // Animate arrow rotation
        val targetRotation = if (chatRoomsExpanded) 0f else 180f
        ObjectAnimator.ofFloat(ivToggle, "rotation", targetRotation).apply {
            duration = 200
            start()
        }
    }
}
