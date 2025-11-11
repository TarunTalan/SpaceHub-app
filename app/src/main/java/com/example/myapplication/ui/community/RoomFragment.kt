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
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.example.myapplication.data.community.model.DataRoom
import com.example.myapplication.data.community.repository.CommunityRepository
import com.example.myapplication.data.user.UserDataManager
import com.example.myapplication.ui.community.adapter.RoomAdapter
import com.example.myapplication.ui.community.adapter.VoiceRoomAdapter
import com.example.myapplication.ui.voice.VoiceRoomViewModel
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

class RoomFragment : Fragment(R.layout.fragment_room) {

    private val roomViewModel: RoomViewModel by viewModels()
    private val voiceVm: VoiceRoomViewModel by viewModels()

    private val chatRooms = mutableListOf<DataRoom>()
    private lateinit var chatRoomsAdapter: RoomAdapter
    private var chatRoomsExpanded = true

    // Track whether we have attempted/created default rooms during this fragment lifecycle
    private var chatDefaultCreated = false
    private var voiceDefaultCreated = false

    private val voiceRooms = mutableListOf<com.example.myapplication.data.voice.model.VoiceRoomX>()
    private lateinit var voiceRoomsAdapter: VoiceRoomAdapter
    private var voiceRoomsExpanded = true

    // keep args as properties so dialog can access them
    private var currentCommunityId: String? = null
    private var currentRoomId: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get arguments
        val communityId = arguments?.getString("communityId")
        val roomId = arguments?.getString("roomId")
        // Prefer server-side roomId for backend calls (createVoiceRoom/getVoiceRooms). roomCode may be an alternate local code.
        val roomCodeArg = arguments?.getString("roomCode")
        currentCommunityId = communityId
        // Use the actual roomId (server id) primarily; fallback to roomCodeArg if needed
        currentRoomId = roomId ?: roomCodeArg
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

        // roomId is guaranteed non-null/non-blank beyond this point (nav arg). Capture a non-null local copy
        val navRoomId: String = roomId.trim()

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
        val tvRoomNameHeader: TextView? = view.findViewById(R.id.tvRoomName)
        val tvUsername: TextView? = view.findViewById(R.id.tvUsername)
        val settingsButton = view.findViewById<ImageView>(R.id.setting_community)
        val communityImage = view.findViewById<ImageView>(R.id.community_image)
        val communityNameTv = view.findViewById<TextView>(R.id.community_name)
        val memberCountTv = view.findViewById<TextView>(R.id.member_count_tv)
        val adminCountTv = view.findViewById<TextView>(R.id.admin_count_tv)
        val rvChatRooms = view.findViewById<RecyclerView>(R.id.rv_chat_rooms)
        val fabCreateRoom = view.findViewById<FloatingActionButton>(R.id.fab_create_room)

        val ivToggleVoice = view.findViewById<ImageView>(R.id.iv_toggle_voice_comm)
        val rvVoiceRooms = view.findViewById<RecyclerView>(R.id.rv_voice_rooms)
        val fabCreateVoice = view.findViewById<FloatingActionButton>(R.id.fab_create_voice_room)

        // Set back button
        backButton?.setOnClickListener {
            findNavController().navigateUp()
        }

        // Set top header room name (title). Use raw roomName if provided, otherwise fallback.
        val headerName = if (roomName.isNullOrBlank()) {
            getString(R.string.app_name)
        } else {
            // prefer showing the plain room name in the top header (no leading '#')
            if (roomName.startsWith("#")) roomName.removePrefix("#") else roomName
        }
        tvRoomNameHeader?.text = headerName
        // Enable marquee if the title is long
        tvRoomNameHeader?.isSelected = true

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
                    // Open the chat UI for the selected chat room.
                    // Use the chatRoom.roomCode when available, otherwise fall back to server id.
                    val code = chatRoom.roomCode.ifBlank { chatRoom.id }
                    val bundle = Bundle().apply {
                        putString("chatRoomCode", code)
                        putString("chatRoomName", chatRoom.name)
                        putString("communityImageUrl", communityImageUrl)
                    }
                    // Navigate to ChatRoomFragment
                    findNavController().navigate(R.id.chatRoomFragment, bundle)
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

        // Setup voice rooms adapter: clicking a voice room opens the voice UI
        voiceRoomsAdapter = VoiceRoomAdapter(onClick = { vr ->
            // Safely navigate to VoiceRoomFragment passing required args
            try {
                val args = Bundle().apply {
                    // roomId: prefer server-side navRoomId, fallback to currentRoomId or vr.roomCode
                    putString("roomId", navRoomId.ifBlank { currentRoomId ?: vr.roomCode })
                    // janusRoomId expected by VoiceRoomFragment (int)
                    putInt("janusRoomId", vr.janusRoomId)
                    putString("voiceRoomName", vr.name)
                }
                // Use safe navigation with try/catch to guard against illegal state
                try { findNavController().navigate(R.id.voiceRoomFragment, args) } catch (e: Exception) {
                    // Fallback: if navController unavailable, show a message and attempt direct join
                    showUiMessage("Cannot open voice room: ${e.message}")
                }
            } catch (e: Exception) {
                showUiMessage("Failed to open voice room: ${e.message}", Snackbar.LENGTH_LONG)
            }
        })
        rvVoiceRooms?.layoutManager = LinearLayoutManager(requireContext())
        rvVoiceRooms?.adapter = voiceRoomsAdapter

        // Observe voice room list state and update UI similar to chat rooms
        viewLifecycleOwner.lifecycleScope.launch {
            voiceVm.listState.collect { state ->
                when (state) {
                    is VoiceRoomViewModel.ListState.Loading -> {
                        view.findViewById<View>(R.id.progress_voice).visibility = View.VISIBLE
                    }
                    is VoiceRoomViewModel.ListState.Success -> {
                        view.findViewById<View>(R.id.progress_voice).visibility = View.GONE
                        try {
                            val list = state.resp.voiceRooms ?: emptyList()
                            voiceRooms.clear()
                            voiceRooms.addAll(list)
                            updateVoiceRoomsUI()
                        } catch (e: Exception) {
                            showUiMessage("Failed to load voice rooms: ${e.message}", Snackbar.LENGTH_LONG)
                        }
                    }
                    is VoiceRoomViewModel.ListState.Error -> {
                        view.findViewById<View>(R.id.progress_voice).visibility = View.GONE
                        showUiMessage(state.msg ?: "Failed to load voice rooms", Snackbar.LENGTH_LONG)
                    }
                    is VoiceRoomViewModel.ListState.Idle -> {
                        view.findViewById<View>(R.id.progress_voice).visibility = View.GONE
                    }
                }
            }
        }

        // Update UI based on chat rooms
        updateChatRoomsUI()
        updateVoiceRoomsUI()

        // Setup expand/collapse toggle
        val ivToggle = view.findViewById<ImageView>(R.id.iv_toggle_your_comm)
        ivToggle?.setOnClickListener {
            chatRoomsExpanded = !chatRoomsExpanded
            applyChatRoomsToggleState(ivToggle)
        }
        ivToggleVoice?.setOnClickListener {
            voiceRoomsExpanded = !voiceRoomsExpanded
            applyVoiceRoomsToggleState(ivToggleVoice)
            // Use the original navigation arg `roomId` (server-side id) for voice APIs
            if (voiceRoomsExpanded) {
                try { voiceVm.getVoiceRooms(navRoomId) } catch (_: Exception) {}
            }
        }

        // Show FAB for creating chat rooms only for community admins/owners/moderators.
        // Start hidden and reveal after checking role.
        fabCreateRoom?.visibility = View.GONE
        fabCreateVoice?.visibility = View.GONE
        fabCreateRoom?.setOnClickListener {
            showCreateChatRoomDialog()
        }
        fabCreateVoice?.setOnClickListener {
            showCreateVoiceRoomDialog(navRoomId)
        }

        // Check community role and reveal FAB for privileged users
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val repo = CommunityRepository.getInstance(requireContext())
                var community = repo.getCommunityById(communityId)
                // If we don't have local info or role flags aren't set, try refreshing members once
                if (community == null || (!community.isOwner && !community.isModerator && community.role.isNullOrBlank())) {
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
                    fabCreateVoice?.visibility = View.VISIBLE
                } else {
                    fabCreateRoom?.visibility = View.GONE
                    fabCreateVoice?.visibility = View.GONE
                }
            } catch (_: Exception) {
                // leave hidden on error
                fabCreateRoom?.visibility = View.GONE
                fabCreateVoice?.visibility = View.GONE
            }
        }

        // Hide settings button (not applicable for room view)
        settingsButton?.visibility = View.GONE

        // Load existing chat rooms for this parent room. Prefer `roomCode` if present.
        roomViewModel.loadChatRoomsForCommunity(communityId, currentRoomId ?: roomId)
        viewLifecycleOwner.lifecycleScope.launch {
            roomViewModel.chatRooms.collect { chatRoomsList ->
                chatRooms.clear()
                // Map DataChatRoom into DataRoom: use server 'id' as the primary id so backend APIs receive correct chatRoom id
                chatRooms.addAll(chatRoomsList.map { dataChatRoom ->
                    DataRoom(id = dataChatRoom.id, name = dataChatRoom.name, roomCode = dataChatRoom.chatRoomCode)
                })
                updateChatRoomsUI()

                // If no chat rooms exist, attempt to create a default one (idempotent per fragment lifecycle and persisted)
                if (chatRoomsList.isEmpty() && !chatDefaultCreated) {
                    chatDefaultCreated = true
                    // Ensure we don't try to create across app restarts if already created earlier
                    val cid = communityId ?: return@collect
                    val rid = currentRoomId ?: roomId ?: return@collect
                    try {
                        val udm = UserDataManager.getInstance(requireContext())
                        val alreadyCreated = try {
                            // DataStore read
                            withContext(Dispatchers.IO) { udm.isDefaultRoomCreated(cid, rid, "chat") }
                        } catch (e: Exception) {
                            false
                        }

                        if (!alreadyCreated) {
                            createDefaultChatRoom(cid, rid)
                        }
                    } catch (e: Exception) {
                        showUiMessage("Failed to create default chat room: ${e.message}", Snackbar.LENGTH_LONG)
                    }
                }

                // If header is still generic, try resolving the parent room's name from repository (network/local)
                communityId.let { cid ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val repo = CommunityRepository.getInstance(requireContext())
                            val res = withContext(Dispatchers.IO) { repo.getAllRooms(cid) }
                            val rooms = res.getOrNull().orEmpty()
                            val lookupKey = currentRoomId ?: roomId
                            val match = rooms.firstOrNull { r -> r.id == lookupKey || r.roomCode == lookupKey || r.name == lookupKey }
                            match?.let { rv ->
                                tvRoomNameHeader?.text = rv.name
                                tvRoomNameHeader?.isSelected = true
                            }
                        } catch (_: Exception) {
                            // ignore lookup failures
                        }
                    }
                }
            }
        }

        // Proactively load voice rooms for this server room id so we can create a default if none exist
        try { voiceVm.getVoiceRooms(navRoomId) } catch (_: Exception) {}
        viewLifecycleOwner.lifecycleScope.launch {
            voiceVm.listState.collect { state ->
                when (state) {
                    is VoiceRoomViewModel.ListState.Success -> {
                        val list = state.resp.voiceRooms
                        val nonEmpty = (list != null && list.isNotEmpty())
                        if (!nonEmpty && !voiceDefaultCreated) {
                            voiceDefaultCreated = true
                            val cid = communityId ?: return@collect
                            val rid = navRoomId
                            try {
                                val udm = UserDataManager.getInstance(requireContext())
                                val alreadyCreated = try {
                                    withContext(Dispatchers.IO) { udm.isDefaultRoomCreated(cid, rid, "voice") }
                                } catch (e: Exception) { false }

                                if (!alreadyCreated) {
                                    createDefaultVoiceRoom(cid, rid)
                                }
                            } catch (e: Exception) {
                                showUiMessage("Failed to create default voice room: ${e.message}", Snackbar.LENGTH_LONG)
                            }
                        }
                    }
                    else -> { /* no-op for other states */ }
                }
            }
        }
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

        // Extract the create action as a named local function to avoid label conflicts
        fun createRoomAction() {
            val name = etName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                tvError.text = getString(R.string.name_required)
                tvError.visibility = View.VISIBLE
                return
            }
            tvError.visibility = View.GONE
            setLoading(true)

            // Capture ids locally to avoid using returns inside inner lambdas
            val cid = currentCommunityId
            val rid = currentRoomId
            if (cid.isNullOrBlank() || rid.isNullOrBlank()) {
                setLoading(false)
                showUiMessage("Missing room context", Snackbar.LENGTH_SHORT)
                return
            }

            // Launch on fragment lifecycle (not viewLifecycleOwner) and perform network IO on Dispatchers.IO
            lifecycleScope.launch {
                try {
                    val repo = CommunityRepository.getInstance(requireContext())
                    val res = withContext(Dispatchers.IO) { repo.createChatRoom(cid, rid, name) }
                    val created = res.getOrNull()

                    if (created != null) {
                        val effectiveId = created.chatRoomCode.ifBlank { created.id }
                        val newRoom = DataRoom(id = effectiveId, name = created.name.ifBlank { name }, roomCode = created.chatRoomCode)
                        // Update UI only if fragment is added and view is present
                        if (isAdded && view != null) {
                            chatRooms.add(0, newRoom)
                            updateChatRoomsUI()
                            Toast.makeText(requireContext(), "Chat room '${created.name}' created", Toast.LENGTH_SHORT).show()
                            try { if (dialog.isShowing) dialog.dismiss() } catch (_: Exception) {}
                        } else {
                            // Fragment not available to update UI; show a message via activity context
                            try { Toast.makeText(requireContext(), "Chat room '${created.name}' created", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                        }
                    } else {
                        val err = res.exceptionOrNull()
                        val sb = makeParentSnackbar(err?.message ?: "Failed to create chat room")
                        if (sb != null) {
                            sb.setAction("Retry") {
                                // Retry using the same safe function
                                createRoomAction()
                            }
                            sb.show()
                        } else {
                            try { Toast.makeText(requireContext(), err?.message ?: "Failed to create chat room", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    showUiMessage("Failed: ${e.message}", Snackbar.LENGTH_LONG)
                } finally {
                    setLoading(false)
                }
            }
        }

        btnCreate.setOnClickListener { createRoomAction() }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showCreateVoiceRoomDialog(serverRoomIdArg: String?) {
         val input = EditText(requireContext())
         input.hint = "Voice room name"
         val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Create voice room")
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val btn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    showUiMessage(getString(R.string.name_required), Snackbar.LENGTH_SHORT)
                    return@setOnClickListener
                }

                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        // show progress if possible
                        view?.findViewById<View>(R.id.progress_voice)?.visibility = View.VISIBLE

                        val createdBy = UserDataManager.getInstance(requireContext()).getEmail() ?: ""
                        // Use server-side roomId nav-arg for creating voice rooms
                        val serverRoomId = serverRoomIdArg ?: return@launch
                        voiceVm.createVoiceRoom(chatRoomId = serverRoomId, roomName = name, createdBy = createdBy)

                        // await next non-loading terminal state
                        val terminal = voiceVm.createState.filter { it !is VoiceRoomViewModel.CreateState.Loading }.first()

                        // hide progress
                        view?.findViewById<View>(R.id.progress_voice)?.visibility = View.GONE

                        when (terminal) {
                            is VoiceRoomViewModel.CreateState.Success -> {
                                val vr = terminal.resp.voiceRoom
                                val mapped = com.example.myapplication.data.voice.model.VoiceRoomX(
                                    active = true,
                                    createdAt = "",
                                    createdBy = vr.createdBy,
                                    id = vr.janusRoomId,
                                    janusRoomId = vr.janusRoomId,
                                    name = vr.name,
                                    roomCode = vr.name
                                )
                                voiceRooms.add(0, mapped)
                                updateVoiceRoomsUI()

                                showUiMessage("Voice room '${vr.name}' created", Snackbar.LENGTH_SHORT)
                            }
                            is VoiceRoomViewModel.CreateState.Error -> {
                                val msg = terminal.msg
                                showUiMessage("Failed: $msg", Snackbar.LENGTH_LONG)
                            }
                            is VoiceRoomViewModel.CreateState.Idle -> {
                                // no-op (create returned to idle without success/error)
                            }
                            is VoiceRoomViewModel.CreateState.Loading -> {
                                // should not happen due to filter, but handle defensively
                            }
                        }
                    } catch (e: Exception) {
                        showUiMessage("Failed to create voice room: ${e.message}", Snackbar.LENGTH_LONG)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun deleteChatRoom(chatRoom: DataRoom) {
        // Confirm first
        com.example.myapplication.ui.common.AppDialogHelper.showConfirmation(requireContext(), "Delete Chat Room", "Are you sure you want to delete '${chatRoom.name}'?", positiveText = "Delete", negativeText = "Cancel", onPositive = {
            // Proceed with delete
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val repo = CommunityRepository.getInstance(requireContext())
                    val res = repo.deleteRoom(currentCommunityId ?: return@launch, chatRoom.id)
                    if (res.isSuccess) {
                        // Remove from list and update UI
                        chatRooms.remove(chatRoom)
                        updateChatRoomsUI()
                        showUiMessage("Chat room '${chatRoom.name}' deleted", Snackbar.LENGTH_SHORT)
                    } else {
                        showUiMessage("Failed to delete chat room: ${res.exceptionOrNull()?.message}", Snackbar.LENGTH_LONG)
                    }
                } catch (e: Exception) {
                    showUiMessage("Failed to delete chat room: ${e.message}", Snackbar.LENGTH_LONG)
                }
            }
        })
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

    private fun updateVoiceRoomsUI() {
        val rvVoice = view?.findViewById<RecyclerView>(R.id.rv_voice_rooms)
        val emptyVoice = view?.findViewById<View>(R.id.empty_voice_rooms_view)

        if (voiceRooms.isEmpty()) {
            emptyVoice?.visibility = View.VISIBLE
            rvVoice?.visibility = View.GONE
        } else {
            emptyVoice?.visibility = View.GONE
            rvVoice?.visibility = if (voiceRoomsExpanded) View.VISIBLE else View.GONE
            voiceRoomsAdapter.submitList(voiceRooms.toList())
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

    private fun applyVoiceRoomsToggleState(ivToggle: ImageView) {
        val rvVoiceRooms = view?.findViewById<RecyclerView>(R.id.rv_voice_rooms)

        rvVoiceRooms?.visibility = if (voiceRoomsExpanded && voiceRooms.isNotEmpty()) View.VISIBLE else View.GONE
        val targetRotation = if (voiceRoomsExpanded) 0f else 180f
        ObjectAnimator.ofFloat(ivToggle, "rotation", targetRotation).apply {
            duration = 200
            start()
        }
    }

    // small helpers to safely show Snackbars/Toasts even if fragment view is destroyed
    private fun showUiMessage(msg: String, length: Int = Snackbar.LENGTH_SHORT) {
        val parent = view ?: activity?.findViewById(android.R.id.content)
        if (parent != null) {
            Snackbar.make(parent, msg, length).show()
        } else {
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun makeParentSnackbar(msg: String): Snackbar? {
        val v = view
        return if (v != null) Snackbar.make(v, msg, Snackbar.LENGTH_INDEFINITE) else activity?.findViewById<View>(android.R.id.content)?.let { Snackbar.make(it, msg, Snackbar.LENGTH_INDEFINITE) }
    }

    private suspend fun createDefaultChatRoom(communityId: String, roomId: String) {
        // Create a default chat room named after the community if none exist
        val defaultRoomName = "General Chat"
        val repo = CommunityRepository.getInstance(requireContext())
        val res = withContext(Dispatchers.IO) { repo.createChatRoom(communityId, roomId, defaultRoomName) }
        val created = res.getOrNull()

        if (created != null) {
            val effectiveId = created.chatRoomCode.ifBlank { created.id }
            val newRoom = DataRoom(id = effectiveId, name = created.name.ifBlank { defaultRoomName }, roomCode = created.chatRoomCode)
            chatRooms.add(0, newRoom)
            updateChatRoomsUI()
            showUiMessage("Default chat room '${created.name}' created", Snackbar.LENGTH_SHORT)
        } else {
            val err = res.exceptionOrNull()
            val sb = makeParentSnackbar(err?.message ?: "Failed to create chat room")
            if (sb != null) {
                sb.setAction("Retry") {
                    // Retry using the same safe function
                    viewLifecycleOwner.lifecycleScope.launch { createDefaultChatRoom(communityId, roomId) }
                }
                sb.show()
            } else {
                showUiMessage(err?.message ?: "Failed to create chat room", Snackbar.LENGTH_LONG)
            }
        }
    }

    private suspend fun createDefaultVoiceRoom(communityId: String, roomId: String) {
        // Create a default voice room named 'General' if none exist
        val defaultVoiceRoomName = "General"
        // Use the server room id (roomId) as chatRoomId for the voice API
        voiceVm.createVoiceRoom(chatRoomId = roomId, roomName = defaultVoiceRoomName, createdBy = "")
        // await next non-loading terminal state
        val terminal = voiceVm.createState.filter { it !is VoiceRoomViewModel.CreateState.Loading }.first()

        when (terminal) {
            is VoiceRoomViewModel.CreateState.Success -> {
                val vr = terminal.resp.voiceRoom
                val mapped = com.example.myapplication.data.voice.model.VoiceRoomX(
                    active = true,
                    createdAt = "",
                    createdBy = vr.createdBy,
                    id = vr.janusRoomId,
                    janusRoomId = vr.janusRoomId,
                    name = vr.name,
                    roomCode = vr.name
                )
                voiceRooms.add(0, mapped)
                updateVoiceRoomsUI()

                showUiMessage("Default voice room '${vr.name}' created", Snackbar.LENGTH_SHORT)
                // persist marker so we don't recreate across restarts
                try {
                    val udm = UserDataManager.getInstance(requireContext())
                    udm.markDefaultRoomCreatedAsync(communityId, roomId, "voice")
                } catch (_: Exception) {}
            }
            is VoiceRoomViewModel.CreateState.Error -> {
                val msg = terminal.msg
                showUiMessage("Failed to create voice room: $msg", Snackbar.LENGTH_LONG)
            }
            is VoiceRoomViewModel.CreateState.Idle -> {
                // no-op (create returned to idle without success/error)
            }
            is VoiceRoomViewModel.CreateState.Loading -> {
                // should not happen due to filter, but handle defensively
            }
        }
    }
}
