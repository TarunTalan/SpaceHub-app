package com.example.myapplication.ui.community

import android.animation.ObjectAnimator
import android.os.Bundle
import android.util.Log
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
import com.example.myapplication.data.voice.VoiceRoomRepository
import com.example.myapplication.ui.community.adapter.RoomAdapter
import com.example.myapplication.ui.community.adapter.VoiceRoomAdapter
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RoomFragment : Fragment(R.layout.fragment_room) {

    private val roomViewModel: RoomViewModel by viewModels()
    private val voiceRepo by lazy { VoiceRoomRepository.getInstance(requireContext()) }

    private val chatRooms = mutableListOf<DataRoom>()
    private lateinit var chatRoomsAdapter: RoomAdapter
    private var chatRoomsExpanded = true

    private val voiceRooms = mutableListOf<com.example.myapplication.data.voice.model.VoiceRoomX>()
    private lateinit var voiceRoomsAdapter: VoiceRoomAdapter
    private var voiceRoomsExpanded = true

    private var currentCommunityId: String? = null
    private var currentRoomId: String? = null
    private var fragmentRootView: View? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fragmentRootView = view
        val rootView: View = view

        val communityId = arguments?.getString("communityId")
        val roomId = arguments?.getString("roomId")
        val roomCodeArg = arguments?.getString("roomCode")
        currentCommunityId = communityId
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

        val navRoomId: String = roomId.trim()

        // Resolve room code if needed
        roomViewModel.startResolve(communityId, currentRoomId ?: roomId)
        viewLifecycleOwner.lifecycleScope.launch {
            roomViewModel.resolvedRoomCode.collect { code ->
                if (!code.isNullOrBlank()) currentRoomId = code
            }
        }

        // Views
        val backButton: ImageView? = rootView.findViewById(R.id.imageView)
        val tvRoomNameHeader: TextView? = rootView.findViewById(R.id.tvRoomName)
        val tvUsername: TextView? = rootView.findViewById(R.id.tvUsername)
        val settingsButton = rootView.findViewById<ImageView>(R.id.setting_community)
        val communityImage = rootView.findViewById<ImageView>(R.id.community_image)
        val communityNameTv = rootView.findViewById<TextView>(R.id.community_name)
        val memberCountTv = rootView.findViewById<TextView>(R.id.member_count_tv)
        val adminCountTv = rootView.findViewById<TextView>(R.id.admin_count_tv)
        val rvChatRooms = rootView.findViewById<RecyclerView>(R.id.rv_chat_rooms)
        val ivToggleVoice = rootView.findViewById<ImageView>(R.id.iv_toggle_voice_comm)
        val rvVoiceRooms = rootView.findViewById<RecyclerView>(R.id.rv_voice_rooms)

        backButton?.setOnClickListener { findNavController().navigateUp() }

        val headerName =
            if (roomName.isNullOrBlank()) getString(R.string.app_name) else if (roomName.startsWith("#")) roomName.removePrefix(
                "#"
            ) else roomName
        tvRoomNameHeader?.text = headerName
        tvRoomNameHeader?.isSelected = true

        communityNameTv?.text = communityName ?: "Community"
        memberCountTv?.text = memberCount.toString()
        adminCountTv?.text = adminCount.toString()

        if (!communityImageUrl.isNullOrBlank()) {
            Glide.with(requireContext()).load(communityImageUrl).placeholder(R.drawable.default_comm_icon)
                .error(R.drawable.default_comm_icon).circleCrop().into(communityImage)
        }

        // Username
        viewLifecycleOwner.lifecycleScope.launch {
            UserDataManager.getInstance(requireContext()).usernameFlow.collect { username ->
                tvUsername?.text = username ?: "User"
            }
        }

        // Chat adapter
        chatRoomsAdapter = RoomAdapter(onClick = { chatRoom ->
            try {
                val code = chatRoom.roomCode.ifBlank { chatRoom.id }
                val bundle = Bundle().apply {
                    putString("chatRoomCode", code)
                    putString("chatRoomName", chatRoom.name)
                    putString("communityImageUrl", communityImageUrl)
                }
                findNavController().navigate(R.id.chatRoomFragment, bundle)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed to open chat: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, onLongClick = { chatRoom ->
            // The actual confirmation dialog is shown inside `deleteChatRoom` — avoid showing it here to prevent duplicate dialogs.
            deleteChatRoom(chatRoom)
        })

        rvChatRooms?.layoutManager = LinearLayoutManager(requireContext())
        rvChatRooms?.adapter = chatRoomsAdapter

        // Voice adapter
        voiceRoomsAdapter = VoiceRoomAdapter(onClick = { vr ->
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    fragmentRootView?.findViewById<View>(R.id.progress_loader)?.visibility = View.VISIBLE
                    val displayName = withContext(Dispatchers.IO) {
                        try {
                            UserDataManager.getInstance(requireContext()).getEmail() ?: ""
                        } catch (_: Exception) {
                            ""
                        }
                    }
                    val res = withContext(Dispatchers.IO) {
                        try {
                            voiceRepo.joinVoiceRoom(vr.janusRoomId, displayName)
                        } catch (t: Throwable) {
                            Result.failure<com.example.myapplication.data.voice.model.JoinVoiceRoomResponse>(t)
                        }
                    }
                    fragmentRootView?.findViewById<View>(R.id.progress_loader)?.visibility = View.GONE
                    if (res.isSuccess) {
                        val resp = res.getOrNull()!!
                        val args = Bundle().apply {
                            putString("roomId", navRoomId.ifBlank { currentRoomId ?: vr.roomCode })
                            putInt("janusRoomId", vr.janusRoomId)
                            putString("voiceRoomName", vr.name)
                            putString("sessionId", resp.sessionId)
                            putString("handleId", resp.handleId)
                        }
                        findNavController().navigate(R.id.voiceRoomFragment, args)
                    } else {
                        showUiMessage("Failed to join voice room: ${res.exceptionOrNull()?.message}")
                    }
                } catch (e: Exception) {
                    fragmentRootView?.findViewById<View>(R.id.progress_loader)?.visibility = View.GONE
                    showUiMessage("Failed to join voice room: ${e.message}")
                }
            }
        }, onLongClick = { vr ->
            // Confirm deletion of voice room (resolve server parent id first)
            try {
                com.example.myapplication.ui.common.AppDialogHelper.showConfirmation(
                    requireContext(),
                    "Delete Voice Room",
                    "Are you sure you want to delete '${vr.name}'?",
                    positiveText = "Delete",
                    negativeText = "Cancel",
                    onPositive = {
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                val requester = try {
                                    withContext(Dispatchers.IO) {
                                        UserDataManager.getInstance(requireContext()).getEmail()
                                    }
                                } catch (_: Exception) {
                                    null
                                }
                                val candidate = currentRoomId ?: navRoomId
                                if (candidate.isNullOrBlank()) {
                                    showUiMessage("Missing parent chat room id"); return@launch
                                }
                                // Resolve to server `id` (not roomCode)
                                val parentId = try {
                                    withContext(Dispatchers.IO) { resolveChatParentId(candidate) }
                                } catch (_: Exception) {
                                    candidate
                                }
                                if (parentId.isNullOrBlank()) {
                                    showUiMessage("Missing resolved parent id"); return@launch
                                }
                                val res = withContext(Dispatchers.IO) {
                                    try {
                                        voiceRepo.deleteVoiceRoom(parentId, vr.name, requester ?: "")
                                    } catch (t: Throwable) {
                                        Result.failure<Unit>(t)
                                    }
                                }
                                if (res.isSuccess) {
                                    // reload authoritative list
                                    Log.d(
                                        "RoomFragment",
                                        "deleteVoiceRoom: deleted '${vr.name}' on parentId='$parentId'"
                                    )
                                    try {
                                        loadVoiceRooms(parentId)
                                    } catch (_: Exception) {
                                    }
                                    showUiMessage("Voice room '${vr.name}' deleted")
                                } else {
                                    Log.w(
                                        "RoomFragment",
                                        "deleteVoiceRoom: failed to delete '${vr.name}' on parentId='$parentId' -> ${res.exceptionOrNull()?.message}"
                                    )
                                    showUiMessage("Failed to delete voice room: ${res.exceptionOrNull()?.message}")
                                }
                            } catch (e: Exception) {
                                showUiMessage("Failed to delete voice room: ${e.message}")
                            }
                        }
                    })
            } catch (_: Exception) {
            }
        })

        rvVoiceRooms?.layoutManager = LinearLayoutManager(requireContext())
        rvVoiceRooms?.adapter = voiceRoomsAdapter

        // Ensure both sections expanded by default
        chatRoomsExpanded = true
        voiceRoomsExpanded = true
        try {
            rootView.findViewById<ImageView>(R.id.iv_toggle_your_comm)?.rotation = 0f
        } catch (_: Exception) {
        }
        try {
            rootView.findViewById<ImageView>(R.id.iv_toggle_voice_comm)?.rotation = 0f
        } catch (_: Exception) {
        }

        updateChatRoomsUI()
        updateVoiceRoomsUI()

        // Toggles (hide/show only)
        val ivToggle = rootView.findViewById<ImageView>(R.id.iv_toggle_your_comm)
        ivToggle?.setOnClickListener {
            chatRoomsExpanded = !chatRoomsExpanded
            applyChatRoomsToggleState(ivToggle)
        }
        ivToggleVoice?.setOnClickListener {
            voiceRoomsExpanded = !voiceRoomsExpanded
            applyVoiceRoomsToggleState(ivToggleVoice)
            // Do NOT trigger network load here; we only hide/show on toggle as requested
        }

        if (voiceRooms.isEmpty()) {
            try {
                loadVoiceRooms(navRoomId)
            } catch (_: Exception) {
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val repo = CommunityRepository.getInstance(requireContext())
                var community = repo.getCommunityById(communityId)
                if (community == null || (!community.isOwner && !community.isModerator && community.role.isNullOrBlank())) {
                    try {
                        repo.fetchMembers(communityId, force = true)
                    } catch (_: Exception) {
                    }
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
                } catch (_: Exception) {
                    false
                }
                // If admin, creation action is available via settings menu
            } catch (_: Exception) {
            }
        }

        // Show settings/hamburger menu only for admins; compute admin status then wire popup when visible.
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val repo = CommunityRepository.getInstance(requireContext())
                // Refresh member roles for an accurate admin check (best-effort)
                try { repo.fetchMembers(communityId, force = true) } catch (_: Exception) {}
                val comm = try { repo.getCommunityById(communityId) } catch (_: Exception) { null }
                val email = try { UserDataManager.getInstance(requireContext()).getEmail() } catch (_: Exception) { null }
                val roleRaw = comm?.role?.trim()?.uppercase()
                val roleIndicatesAdmin = roleRaw?.let { r -> listOf("ADMIN", "OWNER", "CREATOR", "MANAGER", "MODERATOR").any { it in r } } ?: false
                val isOwner = if (comm != null) { comm.isOwner || comm.isModerator || roleIndicatesAdmin || (comm.creatorId?.equals(email, true) == true) } else false
                val isAdmin = isOwner || (roleRaw != null && listOf("OWNER", "CREATOR", "ADMIN", "MODERATOR", "MANAGER").any { roleRaw.contains(it) }) || (comm?.isModerator == true)

                // Set visibility on the UI thread
                try { activity?.runOnUiThread { settingsButton?.visibility = if (isAdmin) View.VISIBLE else View.GONE } } catch (_: Exception) {}

                // If not admin, skip wiring the click handler. If admin, wire same popup as before.
                if (!isAdmin) return@launch

                settingsButton?.setOnClickListener { anchor ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val repo = CommunityRepository.getInstance(requireContext())
                            // Ensure member roles are refreshed so admin checks are accurate
                            try { repo.fetchMembers(currentCommunityId ?: return@launch, force = true) } catch (_: Exception) {}
                            val comm = try { repo.getCommunityById(currentCommunityId ?: return@launch) } catch (_: Exception) { null }
                            val email = try { UserDataManager.getInstance(requireContext()).getEmail() } catch (_: Exception) { null }
                            val role = comm?.role?.uppercase()
                            val adminRoles = setOf("OWNER", "CREATOR", "ADMIN", "MODERATOR", "MANAGER")
                            val isOwner = if (comm != null) { comm.isOwner || (comm.creatorId?.equals(email, true) == true) || (role in listOf("OWNER", "CREATOR")) } else false
                            val isAdmin = isOwner || (role != null && adminRoles.any { role.contains(it) }) || (comm?.isModerator == true)

                            // Use a minimal room-specific menu containing only Add room & Add voice room
                            val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
                            popup.menuInflater.inflate(R.menu.menu_room_actions, popup.menu)
                            // Only admin users should see the menu; items are admin-only by design.
                            popup.setOnMenuItemClickListener { item ->
                                when (item.itemId) {
                                    R.id.action_add_room -> {
                                        try { showCreateChatRoomDialog() } catch (_: Exception) {}
                                        true
                                    }
                                    R.id.action_add_voice_room -> {
                                        try { showCreateVoiceRoomDialog(navRoomId) } catch (_: Exception) {}
                                        true
                                    }
                                    else -> false
                                }
                            }
                            popup.show()
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        roomViewModel.loadChatRoomsForCommunity(communityId, currentRoomId ?: roomId)
        viewLifecycleOwner.lifecycleScope.launch {
            roomViewModel.chatRooms.collect { chatRoomsList ->
                chatRooms.clear()
                chatRooms.addAll(chatRoomsList.map { dataChatRoom ->
                    DataRoom(
                        id = dataChatRoom.id,
                        name = dataChatRoom.name,
                        roomCode = dataChatRoom.chatRoomCode
                    )
                })
                updateChatRoomsUI()

                // try resolve parent room name
                communityId.let { cid ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val repo = CommunityRepository.getInstance(requireContext())
                            val res = withContext(Dispatchers.IO) { repo.getAllRooms(cid) }
                            val rooms = res.getOrNull().orEmpty()
                            val lookupKey = currentRoomId ?: navRoomId
                            val match =
                                rooms.firstOrNull { r -> r.id == lookupKey || r.roomCode == lookupKey || r.name == lookupKey }
                            match?.let { rv -> tvRoomNameHeader?.text = rv.name; tvRoomNameHeader?.isSelected = true }
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }

        // Pull-to-refresh
        try {
            val swipeRefresh =
                rootView.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout?>(R.id.swipe_refresh)
            swipeRefresh?.setOnRefreshListener { refreshLists(communityId, navRoomId) }
        } catch (_: Exception) {
        }

    }

    // Refresh chat rooms (voice kept explicit)
    private fun refreshLists(communityId: String?, serverRoomId: String?) {
        if (communityId.isNullOrBlank() || serverRoomId.isNullOrBlank()) return
        val swipe =
            fragmentRootView?.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout?>(R.id.swipe_refresh)
        lifecycleScope.launch {
            try {
                swipe?.isRefreshing = true
                try {
                    roomViewModel.loadChatRoomsForCommunity(communityId, serverRoomId)
                } catch (_: Exception) {
                }
            } finally {
                swipe?.isRefreshing = false
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
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setView(dialogView).create()
        } catch (_: Exception) {
            com.example.myapplication.ui.common.AppDialogHelper.createViewDialog(
                requireContext(),
                title = null,
                customView = dialogView,
                positiveText = null,
                negativeText = null,
                cancelable = true
            )
        }

        fun setLoading(loading: Boolean) {
            btnCreate.isEnabled = !loading; btnCancel.isEnabled = !loading; etName.isEnabled = !loading
        }

        fun createRoomAction() {
            val name = etName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                tvError.text = getString(R.string.name_required); tvError.visibility = View.VISIBLE; return
            }
            tvError.visibility = View.GONE
            setLoading(true)
            val cid = currentCommunityId;
            val rid = currentRoomId
            if (cid.isNullOrBlank() || rid.isNullOrBlank()) {
                setLoading(false); showUiMessage("Missing room context", Snackbar.LENGTH_SHORT); return
            }

            lifecycleScope.launch {
                try {
                    val repo = CommunityRepository.getInstance(requireContext())
                    val res = withContext(Dispatchers.IO) { repo.createChatRoom(cid, rid, name) }
                    val created = res.getOrNull()
                    if (created != null) {
                        val newRoomId = created.id
                        val newRoom = DataRoom(
                            id = newRoomId,
                            name = created.name.ifBlank { name },
                            roomCode = created.chatRoomCode
                        )
                        if (isAdded) {
                            chatRooms.add(0, newRoom)
                            updateChatRoomsUI()
                            // Success: silent (no toast/snackbar)
                            try {
                                if (dialog.isShowing) dialog.dismiss()
                            } catch (_: Exception) {
                            }
                        }

                        // Create a default voice room under the server-returned chatRoom id (created.id). This aligns with getVoiceRooms(serverRoomId).
                        cid.let { nonNullCid ->
                            lifecycleScope.launch {
                                try {
                                    if (!newRoomId.isNullOrBlank()) createDefaultVoiceRoom(
                                        nonNullCid,
                                        newRoomId
                                    ) else createDefaultVoiceRoom(nonNullCid, rid)
                                } catch (_: Exception) { /* best-effort */
                                }
                            }
                        }
                    } else {
                        val err = res.exceptionOrNull()
                        val sb = makeParentSnackbar(err?.message ?: "Failed to create chat room")
                        sb?.setAction("Retry") { createRoomAction() }
                        sb?.show()
                    }
                } catch (_: Exception) {
                    showUiMessage("Failed", Snackbar.LENGTH_LONG)
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
        val inflater = layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_create_chat_room, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_room_name)
        val tvError = dialogView.findViewById<TextView>(R.id.dialog_error)
        val btnCreate = dialogView.findViewById<android.widget.Button>(R.id.btn_create)
        val btnCancel = dialogView.findViewById<android.widget.Button>(R.id.btn_cancel)

        val dialog = try {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setView(dialogView).create()
        } catch (_: Exception) {
            com.example.myapplication.ui.common.AppDialogHelper.createViewDialog(
                requireContext(),
                title = null,
                customView = dialogView,
                positiveText = null,
                negativeText = null,
                cancelable = true
            )
        }

        fun setLoading(loading: Boolean) {
            try {
                btnCreate.isEnabled = !loading
            } catch (_: Exception) {
            }; try {
                btnCancel.isEnabled = !loading
            } catch (_: Exception) {
            }; try {
                etName.isEnabled = !loading
            } catch (_: Exception) {
            }
        }

        btnCreate.setOnClickListener {
            val name = etName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                tvError.text = getString(R.string.name_required); tvError.visibility =
                    View.VISIBLE; return@setOnClickListener
            }
            tvError.visibility = View.GONE; setLoading(true)
            val serverRoomId = serverRoomIdArg
            if (serverRoomId.isNullOrBlank()) {
                setLoading(false); showUiMessage("Missing room id", Snackbar.LENGTH_SHORT); return@setOnClickListener
            }

            lifecycleScope.launch {
                try {
                    fragmentRootView?.findViewById<View>(R.id.progress_loader)?.visibility = View.VISIBLE
                    val createdBy = try {
                        withContext(Dispatchers.IO) { UserDataManager.getInstance(requireContext()).getEmail() }
                    } catch (_: Exception) {
                        null
                    }
                    // Resolve server parent id so we pass `id` (not roomCode)
                    val parentId = try {
                        withContext(Dispatchers.IO) { resolveChatParentId(serverRoomId) }
                    } catch (_: Exception) {
                        serverRoomId
                    }
                    val targetParent = parentId ?: serverRoomId
                    Log.d(
                        "RoomFragment",
                        "showCreateVoiceRoomDialog: creating voice room name='$name' parent='$targetParent'"
                    )
                    val res = withContext(Dispatchers.IO) {
                        try {
                            voiceRepo.createVoiceRoom(targetParent, name, createdBy.orEmpty())
                        } catch (t: Throwable) {
                            Result.failure<com.example.myapplication.data.voice.model.CreateVoiceRoomResponse>(t)
                        }
                    }
                    fragmentRootView?.findViewById<View>(R.id.progress_loader)?.visibility = View.GONE
                    if (res.isSuccess) {
                        val vr = res.getOrNull()!!.voiceRoom
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
                        // reload authoritative list using resolved parent id (with retries for eventual consistency)
                        try {
                            retryLoadVoiceRooms(targetParent)
                        } catch (_: Exception) {
                            try {
                                loadVoiceRooms(targetParent)
                            } catch (_: Exception) {
                            }
                        }
                        try {
                            dialog.dismiss()
                        } catch (_: Exception) {
                        }
                    } else {
                        val msg = res.exceptionOrNull()?.message ?: "Failed to create voice room"
                        val sb = makeParentSnackbar(msg)
                        sb?.setAction("Retry") { /* user can retry by reopening dialog */ }
                        sb?.show()
                    }
                } catch (e: Exception) {
                    fragmentRootView?.findViewById<View>(R.id.progress_loader)?.visibility = View.GONE
                    showUiMessage("Failed to create voice room: ${e.message}")
                } finally {
                    setLoading(false)
                }
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun deleteChatRoom(chatRoom: DataRoom) {
        // Restrict deletion to admins/owners only. Check local community flags first as a fast-path.
        try {
            val cid = currentCommunityId
            if (cid.isNullOrBlank()) {
                showUiMessage("Missing community id", Snackbar.LENGTH_SHORT); return
            }
            lifecycleScope.launch {
                try {
                    val repo = CommunityRepository.getInstance(requireContext())
                    // getCommunityById is a local DB read; safe to call directly
                    val comm = try {
                        repo.getCommunityById(cid)
                    } catch (_: Exception) {
                        null
                    }
                    val email = try {
                        UserDataManager.getInstance(requireContext()).getEmail()
                    } catch (_: Exception) {
                        null
                    }
                    val role = comm?.role?.uppercase()
                    val adminRoles = setOf("OWNER", "CREATOR", "ADMIN", "MODERATOR", "MANAGER")
                    val isOwner = if (comm != null) {
                        comm.isOwner || (comm.creatorId?.equals(email, true) == true) || (role in listOf(
                            "OWNER",
                            "CREATOR"
                        ))
                    } else false
                    val isAdmin =
                        isOwner || (role != null && adminRoles.any { role.contains(it) }) || (comm?.isModerator == true)

                    if (!isAdmin) {
                        showUiMessage(
                            "You don't have permission to delete this room",
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                        )
                        return@launch
                    }

                    com.example.myapplication.ui.common.AppDialogHelper.showConfirmation(
                        requireContext(),
                        "Delete Chat Room",
                        "Are you sure you want to delete '${chatRoom.name}'?",
                        positiveText = "Delete",
                        negativeText = "Cancel",
                        onPositive = {
                            lifecycleScope.launch {
                                try {
                                    // Resolve parent room's roomCode from server/local DB to ensure correct RoomCode parameter
                                    val parentCodeResolved = try {
                                        withContext(Dispatchers.IO) {
                                            val roomsRes = repo.getAllRooms(cid)
                                            val rooms = roomsRes.getOrNull().orEmpty()
                                            val lookupKey = currentRoomId ?: chatRoom.roomCode.ifBlank { chatRoom.id }
                                            val match =
                                                rooms.firstOrNull { r -> r.id == lookupKey || r.roomCode == lookupKey || r.name == lookupKey }
                                            match?.roomCode?.takeIf { it.isNotBlank() } ?: match?.id
                                        }
                                    } catch (_: Exception) {
                                        null
                                    } ?: currentRoomId ?: chatRoom.roomCode.ifBlank { chatRoom.id }

                                    val chatCode = chatRoom.roomCode.ifBlank { chatRoom.id }
                                    val res = withContext(Dispatchers.IO) {
                                        repo.deleteChatRoom(
                                            parentCodeResolved,
                                            chatCode
                                        )
                                    }
                                    if (res.isSuccess) {
                                        chatRooms.remove(chatRoom); updateChatRoomsUI(); showUiMessage(
                                            "Chat room '${chatRoom.name}' deleted",
                                            com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                                        )
                                    } else {
                                        showUiMessage(
                                            "Failed to delete chat room: ${res.exceptionOrNull()?.message}",
                                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                                        )
                                    }
                                } catch (e: Exception) {
                                    showUiMessage(
                                        "Failed to delete chat room: ${e.message}",
                                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                                    )
                                }
                            }
                        })
                } catch (_: Exception) {
                    showUiMessage(
                        "Failed to check permissions",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                    )
                }
            }
        } catch (_: Exception) {
            showUiMessage("Failed to delete chat room", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
        }
    }

    private fun updateChatRoomsUI() {
        val rvChat = view?.findViewById<RecyclerView>(R.id.rv_chat_rooms)
        val emptyRoomsView = view?.findViewById<View>(R.id.empty_rooms_view)
        val emptyBottom = view?.findViewById<View>(R.id.empty_bottom_message)
        if (chatRooms.isEmpty()) {
            emptyRoomsView?.visibility = View.VISIBLE; rvChat?.visibility = View.GONE
        } else {
            emptyRoomsView?.visibility = View.GONE; rvChat?.visibility =
                if (chatRoomsExpanded) View.VISIBLE else View.GONE; chatRoomsAdapter.submitList(chatRooms.toList())
        }
        // Show combined bottom message only when both chat and voice lists are empty
        val voiceEmpty = (voiceRooms.isEmpty())
        val combinedEmptyView = view?.findViewById<View>(R.id.empty_group_view)
        if (chatRooms.isEmpty() && voiceEmpty) {
            emptyBottom?.visibility = View.VISIBLE
            // hide section-specific empty views when showing bottom message
            emptyRoomsView?.visibility = View.GONE
            view?.findViewById<View>(R.id.empty_voice_rooms_view)?.visibility = View.GONE
            combinedEmptyView?.visibility = View.VISIBLE
        } else {
            emptyBottom?.visibility = View.GONE
            combinedEmptyView?.visibility = View.GONE
        }
    }

    private fun updateVoiceRoomsUI() {
        val rvVoice = view?.findViewById<RecyclerView>(R.id.rv_voice_rooms)
        val emptyVoice = view?.findViewById<View>(R.id.empty_voice_rooms_view)
        val emptyBottom = view?.findViewById<View>(R.id.empty_bottom_message)
        val combinedEmptyView = view?.findViewById<View>(R.id.empty_group_view)
        if (voiceRooms.isEmpty()) {
            emptyVoice?.visibility = View.VISIBLE; rvVoice?.visibility = View.GONE
        } else {
            emptyVoice?.visibility = View.GONE; rvVoice?.visibility =
                if (voiceRoomsExpanded) View.VISIBLE else View.GONE; voiceRoomsAdapter.submitList(voiceRooms.toList())
        }
        // Show combined bottom message only when both chat and voice lists are empty
        val chatEmpty = (chatRooms.isEmpty())
        if (voiceRooms.isEmpty() && chatEmpty) {
            emptyBottom?.visibility = View.VISIBLE
            // hide section-specific empties
            view?.findViewById<View>(R.id.empty_rooms_view)?.visibility = View.GONE
            emptyVoice?.visibility = View.GONE
            combinedEmptyView?.visibility = View.VISIBLE
        } else {
            emptyBottom?.visibility = View.GONE
            combinedEmptyView?.visibility = View.GONE
        }
        try {
            val rv = view?.findViewById<RecyclerView>(R.id.rv_voice_rooms)
            val adapterCount = rv?.adapter?.itemCount ?: -1
            Log.d(
                "RoomFragment",
                "updateVoiceRoomsUI: voiceRooms.size=${voiceRooms.size}, rv.visibility=${rv?.visibility}, adapterCount=$adapterCount"
            )
            rv?.post {
                try {
                    rv.requestLayout()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun applyChatRoomsToggleState(ivToggle: ImageView) {
        val rvChatRooms = view?.findViewById<RecyclerView>(R.id.rv_chat_rooms)
        rvChatRooms?.visibility = if (chatRoomsExpanded && chatRooms.isNotEmpty()) View.VISIBLE else View.GONE
        val targetRotation = if (chatRoomsExpanded) 0f else 180f
        ObjectAnimator.ofFloat(ivToggle, "rotation", targetRotation).apply { duration = 200; start() }
    }

    private fun applyVoiceRoomsToggleState(ivToggle: ImageView) {
        val rvVoiceRooms = view?.findViewById<RecyclerView>(R.id.rv_voice_rooms)
        rvVoiceRooms?.visibility = if (voiceRoomsExpanded && voiceRooms.isNotEmpty()) View.VISIBLE else View.GONE
        val targetRotation = if (voiceRoomsExpanded) 0f else 180f
        ObjectAnimator.ofFloat(ivToggle, "rotation", targetRotation).apply { duration = 200; start() }
    }

    private fun findSnackbarParent(): View? {
        val v = view
        if (v != null && v.isAttachedToWindow) return v
        val activityRoot = activity?.findViewById<View>(android.R.id.content)
        if (activityRoot != null && activityRoot.isAttachedToWindow) return activityRoot
        val decor = activity?.window?.decorView
        if (decor != null && decor.isAttachedToWindow) return decor
        return null
    }

    private fun showUiMessage(msg: String, length: Int = Snackbar.LENGTH_SHORT) {
        try {
            val parent = findSnackbarParent()
            if (parent != null) {
                Snackbar.make(parent, msg, length).show(); return
            }
        } catch (_: IllegalArgumentException) {
        }
        // Keep silent if Snackbar parent not found (avoid Toast)
    }

    private fun makeParentSnackbar(msg: String, autoDismissMs: Long = 5000L): Snackbar? {
        return try {
            val parent = findSnackbarParent()
            if (parent != null) {
                val sb = Snackbar.make(parent, msg, Snackbar.LENGTH_INDEFINITE)
                try {
                    viewLifecycleOwner.lifecycleScope.launch {
                        kotlinx.coroutines.delay(autoDismissMs)
                        try {
                            if (sb.isShown) sb.dismiss()
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                    try {
                        parent.postDelayed({
                            try {
                                if (sb.isShown) sb.dismiss()
                            } catch (_: Exception) {
                            }
                        }, autoDismissMs)
                    } catch (_: Exception) {
                    }
                }
                sb
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun createDefaultChatRoom(communityId: String, roomId: String) {
        val defaultRoomName = "General Chat"
        val repo = CommunityRepository.getInstance(requireContext())
        val res = withContext(Dispatchers.IO) { repo.createChatRoom(communityId, roomId, defaultRoomName) }
        val created = res.getOrNull()
        if (created != null) {
            val effectiveId = created.chatRoomCode.ifBlank { created.id }
            val newRoom = DataRoom(
                id = effectiveId,
                name = created.name.ifBlank { defaultRoomName },
                roomCode = created.chatRoomCode
            )
            chatRooms.add(0, newRoom)
            updateChatRoomsUI()
            try {
                UserDataManager.getInstance(requireContext()).markDefaultRoomCreatedAsync(communityId, roomId, "chat")
            } catch (_: Exception) {
            }
        } else {
            val err = res.exceptionOrNull()
            val sb = makeParentSnackbar(err?.message ?: "Failed to create chat room")
            sb?.setAction("Retry") { lifecycleScope.launch { createDefaultChatRoom(communityId, roomId) } }
            sb?.show()
        }
    }

    private suspend fun resolveChatParentId(candidate: String?): String? {
        if (candidate.isNullOrBlank()) return null
        try {
            val cid = currentCommunityId
            if (cid.isNullOrBlank()) return candidate
            val repo = CommunityRepository.getInstance(requireContext())
            val res = withContext(Dispatchers.IO) { repo.getAllRooms(cid) }
            val rooms = res.getOrNull().orEmpty()
            val match = rooms.firstOrNull { r -> r.id == candidate || r.roomCode == candidate || r.name == candidate }
            return match?.id ?: candidate
        } catch (_: Exception) {
            return candidate
        }
    }

    private suspend fun createDefaultVoiceRoom(communityId: String, roomId: String) {
        val defaultVoiceRoomName = "General"
        try {
            val parentId = resolveChatParentId(roomId) ?: roomId
            Log.d("RoomFragment", "createDefaultVoiceRoom: resolved parentId='$parentId' from candidate='$roomId'")
            val res =
                withContext(Dispatchers.IO) { voiceRepo.createVoiceRoom(parentId ?: roomId, defaultVoiceRoomName, "") }
            if (res.isSuccess) {
                val vr = res.getOrNull()!!.voiceRoom
                Log.d(
                    "RoomFragment",
                    "createDefaultVoiceRoom: server created voice room janusId=${vr.janusRoomId} name=${vr.name}"
                )
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
                // Ensure UI syncs with server authoritative list
                try {
                    if (!parentId.isNullOrBlank()) loadVoiceRooms(parentId)
                    val fallbackId = try {
                        currentRoomId
                    } catch (_: Exception) {
                        null
                    }
                    if (!fallbackId.isNullOrBlank() && fallbackId != parentId) {
                        try {
                            loadVoiceRooms(fallbackId)
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }
                try {
                    UserDataManager.getInstance(requireContext())
                        .markDefaultRoomCreatedAsync(communityId, roomId, "voice")
                } catch (_: Exception) {
                }
            } else {
                Log.w(
                    "RoomFragment",
                    "createDefaultVoiceRoom: failed to create voice room: ${res.exceptionOrNull()?.message}"
                )
                val msg = res.exceptionOrNull()?.message ?: "Unknown"
                val sb = makeParentSnackbar("Failed to create voice room: $msg")
                sb?.setAction("Retry") { lifecycleScope.launch { createDefaultVoiceRoom(communityId, roomId) } }
                sb?.show()
            }
        } catch (_: Exception) {
            showUiMessage("Failed to create voice room")
        }
    }

    private fun loadVoiceRooms(serverRoomId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                fragmentRootView?.findViewById<View>(R.id.progress_loader)?.visibility = View.VISIBLE
                val parentId = try {
                    resolveChatParentId(serverRoomId) ?: serverRoomId
                } catch (_: Exception) {
                    serverRoomId
                }
                Log.d(
                    "RoomFragment",
                    "loadVoiceRooms: resolving parent from candidate='$serverRoomId' -> parentId='$parentId'"
                )
                val res = withContext(Dispatchers.IO) { voiceRepo.getVoiceRooms(parentId ?: serverRoomId) }
                fragmentRootView?.findViewById<View>(R.id.progress_loader)?.visibility = View.GONE
                if (res.isSuccess) {
                    val list = res.getOrNull()?.voiceRooms.orEmpty()
                    Log.d("RoomFragment", "loadVoiceRooms: got ${list.size} voice rooms for parentId='$parentId'")
                    voiceRooms.clear()
                    voiceRooms.addAll(list)
                    updateVoiceRoomsUI()
                } else {
                    val msg = "Failed to load voice rooms: ${res.exceptionOrNull()?.message}"
                    val sb = makeParentSnackbar(msg)
                    sb?.show()
                }
            } catch (_: Exception) {
                fragmentRootView?.findViewById<View>(R.id.progress_loader)?.visibility = View.GONE
                showUiMessage("Failed to load voice rooms")
            }
        }
    }

    // Backend may be eventually consistent; poll the voice-room list for a few attempts after creating a room.
    private fun retryLoadVoiceRooms(parentId: String, attempts: Int = 5, delayMs: Long = 400L) {
        viewLifecycleOwner.lifecycleScope.launch {
            repeat(attempts) { idx ->
                try {
                    val res = withContext(Dispatchers.IO) { voiceRepo.getVoiceRooms(parentId) }
                    if (res.isSuccess) {
                        val list = res.getOrNull()?.voiceRooms.orEmpty()
                        Log.d(
                            "RoomFragment",
                            "retryLoadVoiceRooms: attempt ${idx + 1}/$attempts got ${list.size} rooms for parentId='$parentId'"
                        )
                        if (list.isNotEmpty()) {
                            voiceRooms.clear(); voiceRooms.addAll(list); updateVoiceRoomsUI(); return@launch
                        }
                    } else {
                        Log.w(
                            "RoomFragment",
                            "retryLoadVoiceRooms: attempt ${idx + 1} failed: ${res.exceptionOrNull()?.message}"
                        )
                    }
                } catch (t: Throwable) {
                    Log.w("RoomFragment", "retryLoadVoiceRooms: attempt ${idx + 1} exception: ${t.message}")
                }
                // small delay before next attempt
                try {
                    kotlinx.coroutines.delay(delayMs)
                } catch (_: Exception) {
                }
            }
            // Final attempt: call loadVoiceRooms to surface errors and update UI (may be empty)
            try {
                loadVoiceRooms(parentId)
            } catch (_: Exception) {
            }
        }
    }
}
