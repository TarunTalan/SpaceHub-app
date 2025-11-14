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
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.myapplication.data.voice.VoiceRoomRepository

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
        val fabCreateRoom = rootView.findViewById<FloatingActionButton>(R.id.fab_create_room)

        val ivToggleVoice = rootView.findViewById<ImageView>(R.id.iv_toggle_voice_comm)
        val rvVoiceRooms = rootView.findViewById<RecyclerView>(R.id.rv_voice_rooms)
        val fabCreateVoice = rootView.findViewById<FloatingActionButton>(R.id.fab_create_voice_room)

        backButton?.setOnClickListener { findNavController().navigateUp() }

        val headerName = if (roomName.isNullOrBlank()) getString(R.string.app_name) else if (roomName.startsWith("#")) roomName.removePrefix("#") else roomName
        tvRoomNameHeader?.text = headerName
        tvRoomNameHeader?.isSelected = true

        communityNameTv?.text = communityName ?: "Community"
        memberCountTv?.text = memberCount.toString()
        adminCountTv?.text = adminCount.toString()

        if (!communityImageUrl.isNullOrBlank()) {
            Glide.with(requireContext()).load(communityImageUrl).placeholder(R.drawable.default_comm_icon).error(R.drawable.default_comm_icon).circleCrop().into(communityImage)
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
            com.example.myapplication.ui.common.AppDialogHelper.showConfirmation(requireContext(), "Delete Chat Room", "Are you sure you want to delete '${chatRoom.name}'?", positiveText = "Delete", negativeText = "Cancel", onPositive = {
                deleteChatRoom(chatRoom)
            })
        })

        rvChatRooms?.layoutManager = LinearLayoutManager(requireContext())
        rvChatRooms?.adapter = chatRoomsAdapter

        // Voice adapter
        voiceRoomsAdapter = VoiceRoomAdapter(onClick = { vr ->
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    fragmentRootView?.findViewById<View>(R.id.progress_voice)?.visibility = View.VISIBLE
                    val displayName = withContext(Dispatchers.IO) { try { UserDataManager.getInstance(requireContext()).getEmail() ?: "" } catch (_: Exception) { "" } }
                    val res = withContext(Dispatchers.IO) {
                        try { voiceRepo.joinVoiceRoom(vr.janusRoomId, displayName) }
                        catch (t: Throwable) { Result.failure<com.example.myapplication.data.voice.model.JoinVoiceRoomResponse>(t) }
                    }
                    fragmentRootView?.findViewById<View>(R.id.progress_voice)?.visibility = View.GONE
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
                    fragmentRootView?.findViewById<View>(R.id.progress_voice)?.visibility = View.GONE
                    showUiMessage("Failed to join voice room: ${e.message}")
                }
            }
        })

        rvVoiceRooms?.layoutManager = LinearLayoutManager(requireContext())
        rvVoiceRooms?.adapter = voiceRoomsAdapter

        // Ensure both sections expanded by default
        chatRoomsExpanded = true
        voiceRoomsExpanded = true
        try { rootView.findViewById<ImageView>(R.id.iv_toggle_your_comm)?.rotation = 0f } catch (_: Exception) {}
        try { rootView.findViewById<ImageView>(R.id.iv_toggle_voice_comm)?.rotation = 0f } catch (_: Exception) {}

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
            try { loadVoiceRooms(navRoomId) } catch (_: Exception) {}
        }

        fabCreateRoom?.visibility = View.GONE
        fabCreateVoice?.visibility = View.GONE
        fabCreateRoom?.setOnClickListener { showCreateChatRoomDialog() }
        fabCreateVoice?.setOnClickListener { showCreateVoiceRoomDialog(navRoomId) }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val repo = CommunityRepository.getInstance(requireContext())
                var community = repo.getCommunityById(communityId)
                if (community == null || (!community.isOwner && !community.isModerator && community.role.isNullOrBlank())) {
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
                if (shouldShowFab) { fabCreateRoom?.visibility = View.VISIBLE; fabCreateVoice?.visibility = View.VISIBLE }
            } catch (_: Exception) { fabCreateRoom?.visibility = View.GONE; fabCreateVoice?.visibility = View.GONE }
        }

        settingsButton?.visibility = View.GONE

        roomViewModel.loadChatRoomsForCommunity(communityId, currentRoomId ?: roomId)
        viewLifecycleOwner.lifecycleScope.launch {
            roomViewModel.chatRooms.collect { chatRoomsList ->
                chatRooms.clear()
                chatRooms.addAll(chatRoomsList.map { dataChatRoom -> DataRoom(id = dataChatRoom.id, name = dataChatRoom.name, roomCode = dataChatRoom.chatRoomCode) })
                updateChatRoomsUI()

                // try resolve parent room name
                communityId.let { cid ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val repo = CommunityRepository.getInstance(requireContext())
                            val res = withContext(Dispatchers.IO) { repo.getAllRooms(cid) }
                            val rooms = res.getOrNull().orEmpty()
                            val lookupKey = currentRoomId ?: roomId
                            val match = rooms.firstOrNull { r -> r.id == lookupKey || r.roomCode == lookupKey || r.name == lookupKey }
                            match?.let { rv -> tvRoomNameHeader?.text = rv.name; tvRoomNameHeader?.isSelected = true }
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        // Pull-to-refresh
        try {
            val swipeRefresh = rootView.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout?>(R.id.swipe_refresh)
            swipeRefresh?.setOnRefreshListener { refreshLists(communityId, navRoomId) }
        } catch (_: Exception) {}

    }

    // Refresh chat rooms (voice kept explicit)
    private fun refreshLists(communityId: String?, serverRoomId: String?) {
        if (communityId.isNullOrBlank() || serverRoomId.isNullOrBlank()) return
        val swipe = fragmentRootView?.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout?>(R.id.swipe_refresh)
        lifecycleScope.launch {
            try {
                swipe?.isRefreshing = true
                try { roomViewModel.loadChatRoomsForCommunity(communityId, serverRoomId) } catch (_: Exception) {}
            } finally { swipe?.isRefreshing = false }
        }
    }

    private fun showCreateChatRoomDialog() {
        val inflater = layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_create_chat_room, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_room_name)
        val tvError = dialogView.findViewById<TextView>(R.id.dialog_error)
        val btnCreate = dialogView.findViewById<android.widget.Button>(R.id.btn_create)
        val btnCancel = dialogView.findViewById<android.widget.Button>(R.id.btn_cancel)

        val dialog = try { com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setView(dialogView).create() }
        catch (_: Exception) { com.example.myapplication.ui.common.AppDialogHelper.createViewDialog(requireContext(), title = null, customView = dialogView, positiveText = null, negativeText = null, cancelable = true) }

        fun setLoading(loading: Boolean) { btnCreate.isEnabled = !loading; btnCancel.isEnabled = !loading; etName.isEnabled = !loading }

        fun createRoomAction() {
            val name = etName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) { tvError.text = getString(R.string.name_required); tvError.visibility = View.VISIBLE; return }
            tvError.visibility = View.GONE
            setLoading(true)
            val cid = currentCommunityId; val rid = currentRoomId
            if (cid.isNullOrBlank() || rid.isNullOrBlank()) { setLoading(false); showUiMessage("Missing room context", Snackbar.LENGTH_SHORT); return }

            lifecycleScope.launch {
                try {
                    val repo = CommunityRepository.getInstance(requireContext())
                    val res = withContext(Dispatchers.IO) { repo.createChatRoom(cid, rid, name) }
                    val created = res.getOrNull()
                    if (created != null) {
                        val effectiveId = created.chatRoomCode.ifBlank { created.id }
                        val newRoom = DataRoom(id = effectiveId, name = created.name.ifBlank { name }, roomCode = created.chatRoomCode)
                        if (isAdded) {
                            chatRooms.add(0, newRoom)
                            updateChatRoomsUI()
                            // Success: silent (no toast/snackbar)
                            try { if (dialog.isShowing) dialog.dismiss() } catch (_: Exception) {}
                        }

                        // Also create a default voice room for this chat room (best-effort, don't block the UI)
                        cid.let { nonNullCid ->
                            lifecycleScope.launch {
                                try { createDefaultVoiceRoom(nonNullCid, effectiveId) } catch (_: Exception) { /* best-effort */ }
                            }
                        }
                    } else {
                        val err = res.exceptionOrNull()
                        val sb = makeParentSnackbar(err?.message ?: "Failed to create chat room")
                        sb?.setAction("Retry") { createRoomAction() }
                        sb?.show()
                    }
                } catch (_: Exception) { showUiMessage("Failed", Snackbar.LENGTH_LONG) } finally { setLoading(false) }
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

        val dialog = try { com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setView(dialogView).create() }
        catch (_: Exception) { com.example.myapplication.ui.common.AppDialogHelper.createViewDialog(requireContext(), title = null, customView = dialogView, positiveText = null, negativeText = null, cancelable = true) }

        fun setLoading(loading: Boolean) { try { btnCreate.isEnabled = !loading } catch (_: Exception) {}; try { btnCancel.isEnabled = !loading } catch (_: Exception) {}; try { etName.isEnabled = !loading } catch (_: Exception) {} }

        btnCreate.setOnClickListener {
            val name = etName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) { tvError.text = getString(R.string.name_required); tvError.visibility = View.VISIBLE; return@setOnClickListener }
            tvError.visibility = View.GONE; setLoading(true)
            val serverRoomId = serverRoomIdArg
            if (serverRoomId.isNullOrBlank()) { setLoading(false); showUiMessage("Missing room id", Snackbar.LENGTH_SHORT); return@setOnClickListener }

            lifecycleScope.launch {
                try {
                    fragmentRootView?.findViewById<View>(R.id.progress_voice)?.visibility = View.VISIBLE
                    val createdBy = try { withContext(Dispatchers.IO) { UserDataManager.getInstance(requireContext()).getEmail() } } catch (_: Exception) { null }
                    val res = withContext(Dispatchers.IO) { try { voiceRepo.createVoiceRoom(serverRoomId, name, createdBy.orEmpty()) } catch (t: Throwable) { Result.failure<com.example.myapplication.data.voice.model.CreateVoiceRoomResponse>(t) } }
                    fragmentRootView?.findViewById<View>(R.id.progress_voice)?.visibility = View.GONE
                    if (res.isSuccess) {
                        val vr = res.getOrNull()!!.voiceRoom
                        val mapped = com.example.myapplication.data.voice.model.VoiceRoomX(active = true, createdAt = "", createdBy = vr.createdBy, id = vr.janusRoomId, janusRoomId = vr.janusRoomId, name = vr.name, roomCode = vr.name)
                        voiceRooms.add(0, mapped)
                        updateVoiceRoomsUI()
                        try { dialog.dismiss() } catch (_: Exception) {}
                    } else {
                        val msg = res.exceptionOrNull()?.message ?: "Failed to create voice room"
                        val sb = makeParentSnackbar(msg)
                        sb?.setAction("Retry") { /* user can retry by reopening dialog */ }
                        sb?.show()
                    }
                } catch (e: Exception) {
                    fragmentRootView?.findViewById<View>(R.id.progress_voice)?.visibility = View.GONE
                    showUiMessage("Failed to create voice room: ${e.message}")
                } finally { setLoading(false) }
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun deleteChatRoom(chatRoom: DataRoom) {
        com.example.myapplication.ui.common.AppDialogHelper.showConfirmation(requireContext(), "Delete Chat Room", "Are you sure you want to delete '${chatRoom.name}'?", positiveText = "Delete", negativeText = "Cancel", onPositive = {
            lifecycleScope.launch {
                try {
                    val repo = CommunityRepository.getInstance(requireContext())
                    val cid = currentCommunityId
                    if (cid.isNullOrBlank()) { showUiMessage("Missing community id", Snackbar.LENGTH_SHORT); return@launch }
                    val res = withContext(Dispatchers.IO) { repo.deleteRoom(cid, chatRoom.id) }
                    if (res.isSuccess) { chatRooms.remove(chatRoom); updateChatRoomsUI(); showUiMessage("Chat room '${chatRoom.name}' deleted", Snackbar.LENGTH_SHORT) }
                    else { showUiMessage("Failed to delete chat room: ${res.exceptionOrNull()?.message}", Snackbar.LENGTH_LONG) }
                } catch (_: Exception) { showUiMessage("Failed to delete chat room", Snackbar.LENGTH_LONG) }
            }
        })
    }

    private fun updateChatRoomsUI() {
        val rvChat = view?.findViewById<RecyclerView>(R.id.rv_chat_rooms)
        val emptyRoomsView = view?.findViewById<View>(R.id.empty_rooms_view)
        if (chatRooms.isEmpty()) { emptyRoomsView?.visibility = View.VISIBLE; rvChat?.visibility = View.GONE }
        else { emptyRoomsView?.visibility = View.GONE; rvChat?.visibility = if (chatRoomsExpanded) View.VISIBLE else View.GONE; chatRoomsAdapter.submitList(chatRooms.toList()) }
    }

    private fun updateVoiceRoomsUI() {
        val rvVoice = view?.findViewById<RecyclerView>(R.id.rv_voice_rooms)
        val emptyVoice = view?.findViewById<View>(R.id.empty_voice_rooms_view)
        if (voiceRooms.isEmpty()) { emptyVoice?.visibility = View.VISIBLE; rvVoice?.visibility = View.GONE }
        else { emptyVoice?.visibility = View.GONE; rvVoice?.visibility = if (voiceRoomsExpanded) View.VISIBLE else View.GONE; voiceRoomsAdapter.submitList(voiceRooms.toList()) }
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
            if (parent != null) { Snackbar.make(parent, msg, length).show(); return }
        } catch (_: IllegalArgumentException) {}
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
                        try { if (sb.isShown) sb.dismiss() } catch (_: Exception) {}
                    }
                } catch (_: Exception) {
                    try { parent.postDelayed({ try { if (sb.isShown) sb.dismiss() } catch (_: Exception) {} }, autoDismissMs) } catch (_: Exception) {}
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
            val newRoom = DataRoom(id = effectiveId, name = created.name.ifBlank { defaultRoomName }, roomCode = created.chatRoomCode)
            chatRooms.add(0, newRoom)
            updateChatRoomsUI()
            try { UserDataManager.getInstance(requireContext()).markDefaultRoomCreatedAsync(communityId, roomId, "chat") } catch (_: Exception) {}
        } else {
            val err = res.exceptionOrNull()
            val sb = makeParentSnackbar(err?.message ?: "Failed to create chat room")
            sb?.setAction("Retry") { lifecycleScope.launch { createDefaultChatRoom(communityId, roomId) } }
            sb?.show()
        }
    }

    private suspend fun createDefaultVoiceRoom(communityId: String, roomId: String) {
        val defaultVoiceRoomName = "General"
        try {
            val res = withContext(Dispatchers.IO) { voiceRepo.createVoiceRoom(roomId, defaultVoiceRoomName, "") }
            if (res.isSuccess) {
                val vr = res.getOrNull()!!.voiceRoom
                val mapped = com.example.myapplication.data.voice.model.VoiceRoomX(active = true, createdAt = "", createdBy = vr.createdBy, id = vr.janusRoomId, janusRoomId = vr.janusRoomId, name = vr.name, roomCode = vr.name)
                voiceRooms.add(0, mapped)
                updateVoiceRoomsUI()
                try { UserDataManager.getInstance(requireContext()).markDefaultRoomCreatedAsync(communityId, roomId, "voice") } catch (_: Exception) {}
            } else {
                val msg = res.exceptionOrNull()?.message ?: "Unknown"
                val sb = makeParentSnackbar("Failed to create voice room: $msg")
                sb?.setAction("Retry") { lifecycleScope.launch { createDefaultVoiceRoom(communityId, roomId) } }
                sb?.show()
            }
        } catch (_: Exception) { showUiMessage("Failed to create voice room") }
    }

    private fun loadVoiceRooms(serverRoomId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                fragmentRootView?.findViewById<View>(R.id.progress_voice)?.visibility = View.VISIBLE
                val res = withContext(Dispatchers.IO) { voiceRepo.getVoiceRooms(serverRoomId) }
                fragmentRootView?.findViewById<View>(R.id.progress_voice)?.visibility = View.GONE
                if (res.isSuccess) {
                    val list = res.getOrNull()?.voiceRooms.orEmpty()
                    voiceRooms.clear()
                    voiceRooms.addAll(list)
                    updateVoiceRoomsUI()
                } else {
                    val msg = "Failed to load voice rooms: ${res.exceptionOrNull()?.message}"
                    val sb = makeParentSnackbar(msg)
                    sb?.show()
                }
            } catch (_: Exception) {
                fragmentRootView?.findViewById<View>(R.id.progress_voice)?.visibility = View.GONE
                showUiMessage("Failed to load voice rooms")
            }
        }
    }

}
