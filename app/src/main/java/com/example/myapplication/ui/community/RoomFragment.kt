package com.example.myapplication.ui.community

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
        val backButton = view.findViewById<ImageView>(R.id.imageView)
        val tvUsername = view.findViewById<TextView>(R.id.tvUsername)
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
                Toast.makeText(requireContext(), "Opening chat room: ${chatRoom.name}", Toast.LENGTH_SHORT).show()
                // TODO: Navigate to chat room detail or messages
            },
            onLongClick = { chatRoom ->
                // Show delete dialog
                AlertDialog.Builder(requireContext())
                    .setTitle("Delete Chat Room")
                    .setMessage("Are you sure you want to delete '${chatRoom.name}'?")
                    .setPositiveButton("Delete") { _, _ ->
                        deleteChatRoom(chatRoom)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
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

        // Show FAB for creating chat rooms
        fabCreateRoom?.visibility = View.VISIBLE
        fabCreateRoom?.setOnClickListener {
            showCreateChatRoomDialog()
        }

        // Hide settings button (not applicable for room view)
        settingsButton?.visibility = View.GONE

        // Load existing chat rooms for this parent room. Prefer `roomCode` if present.
        roomViewModel.loadChatRoomsForCommunity(communityId, currentRoomId ?: roomId!!)
        viewLifecycleOwner.lifecycleScope.launch {
            roomViewModel.chatRooms.collect { chatRoomsList ->
                chatRooms.clear()
                // Map DataChatRoom (flat) into DataRoom for adapter compatibility
                chatRooms.addAll(chatRoomsList.map { dataChatRoom ->
                    DataRoom(id = dataChatRoom.roomCode, name = dataChatRoom.name, roomCode = dataChatRoom.roomCode)
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
        val input = EditText(requireContext()).apply {
            hint = "Chat room name"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Create Chat Room")
            .setView(input)
            .setPositiveButton("Create") { dialog, _ ->
                val name = input.text?.toString()?.trim()
                if (!name.isNullOrBlank()) {
                    // call createChatRoom via repository
                    val commId = currentCommunityId
                    val rId = currentRoomId
                    if (commId.isNullOrBlank() || rId.isNullOrBlank()) {
                        Toast.makeText(requireContext(), "Missing room information", Toast.LENGTH_SHORT).show()
                    } else {
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                val repo = CommunityRepository.getInstance(requireContext())
                                val res = repo.createChatRoom(commId, rId, name)
                                val created = res.getOrNull()
                                if (created != null) {
                                    val effectiveId = if (created.roomCode.isNotBlank()) created.roomCode else created.id
                                    val newRoom = DataRoom(id = effectiveId, name = created.name.ifBlank { name }, roomCode = created.roomCode)
                                    chatRooms.add(0, newRoom)
                                    updateChatRoomsUI()
                                    Toast.makeText(requireContext(), "Chat room '${created.name}' created", Toast.LENGTH_SHORT).show()
                                } else {
                                    val err = res.exceptionOrNull()
                                    Toast.makeText(requireContext(), err?.message ?: "Failed to create chat room", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(requireContext(), "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), "Name is required", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
