package com.example.myapplication.ui.group

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.example.myapplication.ui.common.BaseFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.myapplication.ui.community.adapter.RoomAdapter
import com.example.myapplication.data.community.model.DataRoom
import com.example.myapplication.R
import com.example.myapplication.data.user.UserDataManager
import com.example.myapplication.ui.group.viewmodel.GroupDetailViewModel
import com.example.myapplication.ui.group.viewmodel.GroupRoomViewModel
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.ui.community.adapter.VoiceRoomAdapter
import com.example.myapplication.data.voice.VoiceRoomRepository
import com.example.myapplication.data.groups.repository.LocalGroupRepository

class GroupDetailFragment : BaseFragment(R.layout.fragment_group_detail) {
    // Receiver for worker completion broadcasts (initialized in onViewCreated)
    private lateinit var defaultRoomsReceiver: android.content.BroadcastReceiver
    // Use activity-scoped VM so other fragments (members) can share the same instance
    private val vm: GroupDetailViewModel by activityViewModels()
    // ViewModel to manage chat rooms inside this local group
    private val roomsVm: GroupRoomViewModel by viewModels()

    // Voice rooms support (mirror of community RoomFragment)
    private val voiceRepo by lazy { VoiceRoomRepository.getInstance(requireContext()) }
    private val voiceRooms = mutableListOf<com.example.myapplication.data.voice.model.VoiceRoomX>()
    private lateinit var voiceRoomsAdapter: VoiceRoomAdapter
    private var chatRoomsExpanded = true
    private var voiceRoomsExpanded = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Header back arrow (imageView id in layout)
        view.findViewById<View>(R.id.imageView)?.setOnClickListener { try { findNavController().navigateUp() } catch (_: Exception) {} }

        val tvUser = view.findViewById<TextView>(R.id.tvUsername)
        // Header group name TextView (show group name in header)
        val tvGroupNameHeader = view.findViewById<TextView>(R.id.tvGroupName)
        val grpImage = view.findViewById<ShapeableImageView>(R.id.grp_image)
        val grpName = view.findViewById<TextView>(R.id.grp_name)
        val memberCountTv = view.findViewById<TextView>(R.id.member_count_tv)
        val overlay = view.findViewById<View>(R.id.loading_overlay)
        val settingsAnchor = view.findViewById<ImageView>(R.id.setting_grp)
        val rvRooms = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_rooms)
        val emptyRoomsView = view.findViewById<View>(R.id.empty_rooms_view)
        val swipeRefresh = view.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)

        // Voice views and adapter setup
        val rvVoiceRooms = view.findViewById<RecyclerView>(R.id.rv_voice_rooms)
        val emptyVoiceView = view.findViewById<View>(R.id.empty_voice_rooms_view)
        val progressVoice = view.findViewById<View>(R.id.progress_voice)

        // Determine groupId early so lambdas defined below can capture it
        val groupId = arguments?.getString("communityId") ?: arguments?.getString("id")

        voiceRoomsAdapter = VoiceRoomAdapter(onClick = { vr ->
            lifecycleScope.launch {
                try {
                    progressVoice?.visibility = View.VISIBLE
                    val displayName = withContext(Dispatchers.IO) { try { UserDataManager.getInstance(requireContext()).getEmail() ?: "" } catch (_: Exception) { "" } }
                    val res = withContext(Dispatchers.IO) { try { voiceRepo.joinVoiceRoom(vr.janusRoomId, displayName) } catch (t: Throwable) { Result.failure<com.example.myapplication.data.voice.model.JoinVoiceRoomResponse>(t) } }
                    progressVoice?.visibility = View.GONE
                    if (res.isSuccess) {
                        val resp = res.getOrNull()!!
                        val args = Bundle().apply {
                            // Prefer the group's chatRoomId from VM (returned by getLocalGroupDetails); fallback to groupId
                            val effectiveRoomId = vm.group.value?.chatRoomId?.takeIf { it.isNotBlank() } ?: groupId
                            putString("roomId", effectiveRoomId)
                            putInt("janusRoomId", vr.janusRoomId)
                            putString("voiceRoomName", vr.name)
                            putString("sessionId", resp.sessionId)
                            putString("handleId", resp.handleId)
                        }
                        try { findNavController().navigate(R.id.voiceRoomFragment, args) } catch (_: Exception) {}
                    } else {
                        try { com.google.android.material.snackbar.Snackbar.make(requireView(), "Failed to join voice room: ${res.exceptionOrNull()?.message}", com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    progressVoice?.visibility = View.GONE
                    try { com.google.android.material.snackbar.Snackbar.make(requireView(), "Failed to join voice room: ${e.message}", com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show() } catch (_: Exception) {}
                }
            }
        }, onLongClick = { vr ->
            try {
                com.example.myapplication.ui.common.AppDialogHelper.showConfirmation(requireContext(), "Delete Voice Room", "Are you sure you want to delete '${vr.name}'?", positiveText = "Delete", negativeText = getString(android.R.string.cancel), onPositive = {
                    lifecycleScope.launch {
                        try {
                            val requester = try { withContext(Dispatchers.IO) { UserDataManager.getInstance(requireContext()).getEmail() } } catch (_: Exception) { null }
                            val chatRoomId = vm.group.value?.chatRoomId?.takeIf { it.isNotBlank() } ?: groupId
                            if (chatRoomId.isNullOrBlank()) {
                                try { com.google.android.material.snackbar.Snackbar.make(requireView(), "Missing parent chat room id", com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show() } catch (_: Exception) {}
                                return@launch
                            }
                            val res = withContext(Dispatchers.IO) { try { voiceRepo.deleteVoiceRoom(chatRoomId, vr.name, requester ?: "") } catch (t: Throwable) { Result.failure<Unit>(t) } }
                            if (res.isSuccess) {
                                // reload
                                try { loadVoiceRooms(chatRoomId, progressVoice) } catch (_: Exception) {}
                                try { com.google.android.material.snackbar.Snackbar.make(requireView(), "Voice room '${vr.name}' deleted", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show() } catch (_: Exception) {}
                            } else {
                                try { com.google.android.material.snackbar.Snackbar.make(requireView(), "Failed to delete voice room: ${res.exceptionOrNull()?.message}", com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show() } catch (_: Exception) {}
                            }
                        } catch (e: Exception) {
                            try { com.google.android.material.snackbar.Snackbar.make(requireView(), "Failed to delete voice room: ${e.message}", com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show() } catch (_: Exception) {}
                        }
                    }
                })
            } catch (_: Exception) {}
        })
        rvVoiceRooms?.layoutManager = LinearLayoutManager(requireContext())
        rvVoiceRooms?.adapter = voiceRoomsAdapter

        // Ensure toggles are expanded by default (match community behavior)
        try { view.findViewById<ImageView>(R.id.iv_toggle_your_comm)?.rotation = 0f } catch (_: Exception) {}
        try { view.findViewById<ImageView>(R.id.iv_toggle_voice_comm)?.rotation = 0f } catch (_: Exception) {}

        // Make marquee scroll without focus requirement (header username)
        tvUser?.isSelected = true
        // Allow group name in header to marquee as well
        tvGroupNameHeader?.isSelected = true

        // Load username into header
        lifecycleScope.launch {
            try {
                UserDataManager.getInstance(requireContext()).usernameFlow.collect { uname ->
                    if (uname != null && tvUser != null) tvUser.text = uname
                }
            } catch (_: Exception) {}
        }

        if (groupId.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Missing group id", Toast.LENGTH_SHORT).show()
            return
        }

        // wire ViewModel
        vm.setGroupId(groupId)

        // Quick render from passed args if available
        val passedName = arguments?.getString("name")
        val passedImage = arguments?.getString("imageUrl")
        if (!passedName.isNullOrBlank()) grpName?.text = passedName
        // Also show the passed name in the header title to avoid empty header
        if (!passedName.isNullOrBlank()) tvGroupNameHeader?.text = passedName
        if (!passedImage.isNullOrBlank()) {
            try {
                // Let ShapeableImageView apply circular mask; use centerCrop for correct scaling
                Glide.with(this)
                    .load(passedImage)
                    .placeholder(R.drawable.default_comm_icon)
                    .centerCrop()
                    .into(grpImage)
            } catch (_: Exception) {
                Glide.with(this).load(R.drawable.default_comm_icon).centerCrop().into(grpImage)
            }
        } else {
            Glide.with(this).load(R.drawable.default_comm_icon).centerCrop().into(grpImage)
        }

        // observe VM state with debounce: show overlay only if loading persists beyond 300ms
        var overlayShowJob: Job? = null
        vm.loading.observe(viewLifecycleOwner) { loading ->
            if (loading) {
                // cancel any previous show job and start a delayed show
                overlayShowJob?.cancel()
                overlayShowJob = viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        delay(300) // short debounce to avoid flicker on fast loads
                        overlay?.visibility = View.VISIBLE
                    } catch (_: Exception) {}
                }
            } else {
                // cancel pending job and hide immediately
                try { overlayShowJob?.cancel() } catch (_: Exception) {}
                overlayShowJob = null
                overlay?.visibility = View.GONE
            }
        }

        // Setup rooms adapter
        val roomsAdapter = RoomAdapter(onClick = { room ->
            // Navigate to chat room screen, passing chatRoomCode, chatRoomName and group image
            try {
                val code = room.roomCode.ifBlank { room.id }
                val groupImage = try { vm.group.value?.imageUrl as? String } catch (_: Exception) { null }
                val args = Bundle().apply {
                    putString("chatRoomCode", code)
                    putString("chatRoomName", room.name)
                    putString("communityImageUrl", groupImage ?: passedImage)
                }
                this@GroupDetailFragment.navigateWithDelay(R.id.chatRoomFragment, args)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed to open chat: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, onLongClick = { room ->
            // Confirm and delete chat room inside this local group. Resolve parent chatRoomCode first.
            try {
                val ctx = requireContext()
                com.example.myapplication.ui.common.AppDialogHelper.showConfirmation(ctx, "Delete Chat Room", "Are you sure you want to delete '${room.name}'?", positiveText = "Delete", negativeText = ctx.getString(android.R.string.cancel), onPositive = {
                    lifecycleScope.launch {
                        try {
                            val repo = LocalGroupRepository.getInstance(requireContext())
                            // Resolve parent code: prefer vm.group.chatRoomCode; if missing, try to refresh and wait briefly
                            var parentCode = vm.group.value?.chatRoomCode?.takeIf { it.isNotBlank() }
                            if (parentCode.isNullOrBlank()) {
                                try { vm.refreshDetails() } catch (_: Exception) {}
                                // wait briefly (max ~2s) for VM to update
                                var waited = 0
                                while (parentCode.isNullOrBlank() && waited < 2000) {
                                    kotlinx.coroutines.delay(250)
                                    parentCode = vm.group.value?.chatRoomCode?.takeIf { it.isNotBlank() }
                                    waited += 250
                                }
                            }

                            // If still missing, fall back to fragment argument (group id) but warn that deletion may fail if server expects different code
                            if (parentCode.isNullOrBlank()) parentCode = arguments?.getString("communityId") ?: arguments?.getString("id") ?: ""

                            if (parentCode.isBlank()) {
                                try { com.google.android.material.snackbar.Snackbar.make(requireView(), "Unable to determine parent room code; try refreshing and retrying", com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show() } catch (_: Exception) {}
                                return@launch
                            }

                            val targetRoomCode = room.roomCode.ifBlank { room.id }
                            val res = withContext(Dispatchers.IO) { repo.deleteChatRoom(parentCode, targetRoomCode) }
                            if (res.isSuccess) {
                                // refresh the list via ViewModel using resolved parentCode
                                try { roomsVm.loadChatRoomsForGroup(parentCode) } catch (_: Exception) {}
                                try { com.google.android.material.snackbar.Snackbar.make(requireView(), "Chat room '${room.name}' deleted", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show() } catch (_: Exception) {}
                            } else {
                                val msg = res.exceptionOrNull()?.message ?: "Failed to delete chat room"
                                try { com.google.android.material.snackbar.Snackbar.make(requireView(), msg, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show() } catch (_: Exception) {}
                            }
                        } catch (e: Exception) {
                            try { com.google.android.material.snackbar.Snackbar.make(requireView(), "Failed to delete chat room: ${e.message}", com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show() } catch (_: Exception) {}
                        }
                    }
                })
            } catch (_: Exception) {}
        })
        rvRooms?.layoutManager = LinearLayoutManager(requireContext())
        rvRooms?.adapter = roomsAdapter

        // Pull-to-refresh: reload rooms (and refresh group details optionally)
        swipeRefresh?.setOnRefreshListener {
            val code = try { vm.group.value?.chatRoomCode?.takeIf { it.isNotBlank() } ?: groupId } catch (_: Exception) { groupId }
            try { roomsVm.loadChatRoomsForGroup(code) } catch (_: Exception) {}
            // optionally refresh group details as well
            try { vm.refreshDetails() } catch (_: Exception) {}
        }

        // Observe roomsVm.loading to drive the SwipeRefreshLayout indicator
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                roomsVm.loading.collect { loading ->
                    try { swipeRefresh?.isRefreshing = loading } catch (_: Exception) {}
                }
            }
        }

        // Observe chat rooms from roomsVm and update adapter (collected below from lifecycleScope)
        vm.group.observe(viewLifecycleOwner) { data ->
            data?.let { it ->
                // DataXX fields are non-nullable in the model: name:String, totalMembers:Int
                grpName?.text = it.name
                // update header title as well
                tvGroupNameHeader?.text = it.name
                memberCountTv?.text = it.totalMembers.toString()
                val imgUrl = (it.imageUrl as? String)
                if (!imgUrl.isNullOrBlank()) {
                    try {
                        Glide.with(this)
                            .load(imgUrl)
                            .placeholder(R.drawable.default_comm_icon)
                            .centerCrop()
                            .into(grpImage)
                    } catch (_: Exception) {
                        Glide.with(this).load(R.drawable.default_comm_icon).centerCrop().into(grpImage)
                    }
                }

                // Load chat rooms for this group into roomsVm
                val code = it.chatRoomCode.takeIf { it.isNotBlank() } ?: groupId
                try { roomsVm.loadChatRoomsForGroup(code) } catch (_: Exception) {}
            }
        }

        // Observe roomsVm chatRooms and update UI (use lifecycleScope + repeatOnLifecycle to collect StateFlow)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                roomsVm.chatRooms.collect { chatList ->
                    val mapped = chatList.map { c ->
                        DataRoom(id = c.chatRoomCode.ifBlank { c.id }, name = c.name, roomCode = c.chatRoomCode)
                    }
                    if (mapped.isEmpty()) {
                        emptyRoomsView?.visibility = View.VISIBLE
                        rvRooms?.visibility = View.GONE
                    } else {
                        emptyRoomsView?.visibility = View.GONE
                        rvRooms?.visibility = if (chatRoomsExpanded) View.VISIBLE else View.GONE
                    }
                    roomsAdapter.submitList(mapped)
                }
            }
        }

        // Wire toggles (same show/hide behavior as in community RoomFragment)
        val ivToggleYourComm = view.findViewById<ImageView>(R.id.iv_toggle_your_comm)
        val ivToggleVoice = view.findViewById<ImageView>(R.id.iv_toggle_voice_comm)
        ivToggleYourComm?.setOnClickListener {
            chatRoomsExpanded = !chatRoomsExpanded
            applyChatRoomsToggleState(ivToggleYourComm, rvRooms, emptyRoomsView)
        }
        ivToggleVoice?.setOnClickListener {
            voiceRoomsExpanded = !voiceRoomsExpanded
            applyVoiceRoomsToggleState(ivToggleVoice, rvVoiceRooms, emptyVoiceView)
            // load voice rooms when expanding if empty
            if (voiceRoomsExpanded && voiceRooms.isEmpty()) {
                // Prefer the group's chatRoomId from VM (returned by getLocalGroupDetails); fallback to groupId
                val effectiveRoomId = vm.group.value?.chatRoomId?.takeIf { it.isNotBlank() } ?: groupId
                try { loadVoiceRooms(effectiveRoomId, progressVoice) } catch (_: Exception) {}
            }
        }

        // when group resolves, ensure voice rooms loaded once
        vm.group.observe(viewLifecycleOwner) { data ->
            data?.let {
                // Prefer the group's chatRoomId returned by getLocalGroupDetails; fallback to groupId
                val effectiveRoomId = it.chatRoomId.takeIf { it.isNotBlank() } ?: groupId
                try { loadVoiceRooms(effectiveRoomId, progressVoice) } catch (_: Exception) {}
            }
        }

        vm.toast.observe(viewLifecycleOwner) { msg ->
            msg?.let {
                // Show detailed message via Snackbar with a Retry action to re-trigger network fetch
                try {
                    val parent = requireView()
                    com.google.android.material.snackbar.Snackbar.make(parent, it, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                        .setAction("Retry") { try { vm.refreshDetails(); vm.loadMembers() } catch (_: Exception) {} }
                        .show()
                } catch (_: Exception) {}
                // mark toast consumed
                try { vm.clearToast() } catch (_: Exception) {}
            }
        }

        // When a group is deleted, notify dashboard to refresh and navigate up
        vm.deleted.observe(viewLifecycleOwner) { deleted ->
            if (deleted == true) {
                try {
                    val nav = findNavController()
                    val entry = nav.getBackStackEntry(R.id.dashboardFragment)
                    // tell dashboard to refresh and also send the deleted group's id so UI can remove it immediately
                    entry.savedStateHandle["refresh_local_groups"] = true
                    entry.savedStateHandle["local_group_deleted_id"] = groupId
                } catch (_: Exception) {}
                try { findNavController().navigateUp() } catch (_: Exception) {}
                // clear the deleted flag after handling to avoid stale state
                try { vm.clearDeleted() } catch (_: Exception) {}
            }
        }

        // Listen for background worker completion so we can refresh voice rooms immediately
        defaultRoomsReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                try {
                    val gidExtra = intent?.getStringExtra("groupId")
                    // Only react if this broadcast pertains to the same group
                    if (gidExtra == groupId) {
                        val effectiveRoomId = vm.group.value?.chatRoomId?.takeIf { it.isNotBlank() } ?: groupId
                        try { loadVoiceRooms(effectiveRoomId, progressVoice) } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        }
        try { requireContext().registerReceiver(defaultRoomsReceiver, android.content.IntentFilter("com.example.myapplication.ACTION_DEFAULT_ROOMS_CREATED")) } catch (_: Exception) {}

        settingsAnchor?.setOnClickListener { anchor ->
            // Show popup menu same as community detail
            val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
            try {
                popup.menuInflater.inflate(R.menu.menu_group_detail, popup.menu)
                // Local groups don't support 'Leave' via this menu — hide it.
                try { popup.menu.findItem(R.id.action_leave_community)?.isVisible = false } catch (_: Exception) {}
            } catch (_: Exception) {}
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_edit_community -> {
                        // Navigate to edit group fragment, passing the group id
                        try {
                            val args = Bundle().apply { putString("communityId", groupId) }
                            this@GroupDetailFragment.navigateWithDelay(R.id.action_localGroupDetail_to_editGroup, args)
                        } catch (_: Exception) {
                            Toast.makeText(requireContext(), "Failed to open editor", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    R.id.action_invite -> {
                        // Create invite link for local group
                        try {
                            vm.createInviteLink()
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "Failed to create invite: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    R.id.action_add_room -> {
                        try {
                            val ctx = requireContext()
                            val inflater = layoutInflater
                            val dialogView = inflater.inflate(R.layout.dialog_create_chat_room, null)
                            // Resolve IDs at runtime to avoid any R generation timing issues
                            val pkg = requireContext().packageName
                            val etId = dialogView.resources.getIdentifier("et_room_name", "id", pkg)
                            val errId = dialogView.resources.getIdentifier("dialog_error", "id", pkg)
                            val btnCreateId = dialogView.resources.getIdentifier("btn_create", "id", pkg)
                            val btnCancelId = dialogView.resources.getIdentifier("btn_cancel", "id", pkg)
                            val etName = dialogView.findViewById<EditText?>(etId)
                            val tvError = dialogView.findViewById<TextView?>(errId)
                            val btnCreate = dialogView.findViewById<android.widget.Button?>(btnCreateId)
                            val btnCancel = dialogView.findViewById<android.widget.Button?>(btnCancelId)

                            val dialog = try {
                                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                                    .setView(dialogView)
                                    .create()
                            } catch (_: Exception) {
                                // Fallback to AppCompat dialog if Material dialog creation fails (theme missing on some devices)
                                androidx.appcompat.app.AlertDialog.Builder(ctx)
                                    .setView(dialogView)
                                    .create()
                            }

                            fun setLoading(loading: Boolean) {
                                try { btnCreate?.isEnabled = !loading } catch (_: Exception) {}
                                try { btnCancel?.isEnabled = !loading } catch (_: Exception) {}
                                try { etName?.isEnabled = !loading } catch (_: Exception) {}
                            }

                            btnCreate?.setOnClickListener {
                                val name = etName?.text?.toString()?.trim().orEmpty()
                                if (name.isEmpty()) {
                                    try { tvError?.text = "Name is required" } catch (_: Exception) {}
                                    try { tvError?.visibility = View.VISIBLE } catch (_: Exception) {}
                                    return@setOnClickListener
                                }
                                try { tvError?.visibility = View.GONE } catch (_: Exception) {}
                                setLoading(true)
                                val parentCode = try { vm.group.value?.chatRoomCode } catch (_: Exception) { null }
                                val effectiveParent = parentCode.takeIf { !it.isNullOrBlank() } ?: groupId
                                roomsVm.createChatRoom(effectiveParent, name) { res ->
                                    setLoading(false)
                                    if (res.isSuccess) {
                                        // Success: silently refresh the rooms list and close dialog
                                        roomsVm.loadChatRoomsForGroup(effectiveParent)
                                        try { dialog.dismiss() } catch (_: Exception) {}

                                        // Best-effort: create a default voice room for this chat room
                                        try {
                                            val created = res.getOrNull()
                                            if (created != null) {
                                                val voiceRepo = com.example.myapplication.data.voice.VoiceRoomRepository.getInstance(requireContext())
                                                lifecycleScope.launch {
                                                    try {
                                                        val creatorEmail = withContext(Dispatchers.IO) { UserDataManager.getInstance(requireContext()).getEmail() }.orEmpty()
                                                        withContext(Dispatchers.IO) { voiceRepo.createVoiceRoom(created.id, "${created.name} Voice", creatorEmail) }
                                                        // ignore failure; if success we could refresh a voice list if present
                                                    } catch (_: Exception) {}
                                                }
                                            }
                                        } catch (_: Exception) {}

                                    } else {
                                        // Show failure using Snackbar only (no Toast fallback)
                                        try {
                                            com.google.android.material.snackbar.Snackbar.make(requireView(), "Failed to create chat room", com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
                                                .setAction("Retry") { try { roomsVm.loadChatRoomsForGroup(effectiveParent) } catch (_: Exception) {} }
                                                .show()
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                            btnCancel?.setOnClickListener { dialog.dismiss() }
                            dialog.show()
                        } catch (_: Exception) {
                            try { Toast.makeText(requireContext(), "Failed to open create room dialog", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                        }
                        true
                    }
                    R.id.action_add_voice_room -> {
                        // Show dialog to create a voice room directly (uses group's chatRoomId as room identifier)
                        try {
                            val inflater = layoutInflater
                            val dialogView = inflater.inflate(R.layout.dialog_create_chat_room, null)
                            val etName = dialogView.findViewById<EditText>(R.id.et_room_name)
                            val tvError = dialogView.findViewById<TextView>(R.id.dialog_error)
                            val btnCreate = dialogView.findViewById<android.widget.Button>(R.id.btn_create)
                            val btnCancel = dialogView.findViewById<android.widget.Button>(R.id.btn_cancel)

                            val dialog = try { com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setView(dialogView).create() }
                            catch (_: Exception) { androidx.appcompat.app.AlertDialog.Builder(requireContext()).setView(dialogView).create() }

                            fun setLoading(loading: Boolean) { try { btnCreate.isEnabled = !loading } catch (_: Exception) {}; try { btnCancel.isEnabled = !loading } catch (_: Exception) {}; try { etName.isEnabled = !loading } catch (_: Exception) {} }

                            btnCreate.setOnClickListener {
                                val name = etName.text?.toString()?.trim().orEmpty()
                                if (name.isEmpty()) { try { tvError.text = getString(R.string.name_required) } catch (_: Exception) {} ; try { tvError.visibility = View.VISIBLE } catch (_: Exception) {} ; return@setOnClickListener }
                                try { tvError.visibility = View.GONE } catch (_: Exception) {}
                                setLoading(true)
                                val effectiveRoomId = vm.group.value?.chatRoomId?.takeIf { it.isNotBlank() } ?: groupId
                                if (effectiveRoomId.isNullOrBlank()) { setLoading(false); try { com.google.android.material.snackbar.Snackbar.make(requireView(), getString(R.string.missing_token), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show() } catch (_: Exception) {} ; return@setOnClickListener }

                                lifecycleScope.launch {
                                    try {
                                        progressVoice?.visibility = View.VISIBLE
                                        val creatorEmail = withContext(Dispatchers.IO) { try { UserDataManager.getInstance(requireContext()).getEmail() } catch (_: Exception) { "" } }
                                        val res = withContext(Dispatchers.IO) { try { voiceRepo.createVoiceRoom(effectiveRoomId, name, creatorEmail.orEmpty()) } catch (t: Throwable) { Result.failure<com.example.myapplication.data.voice.model.CreateVoiceRoomResponse>(t) } }
                                        progressVoice?.visibility = View.GONE
                                        if (res.isSuccess) {
                                            val vr = res.getOrNull()!!.voiceRoom
                                            val mapped = com.example.myapplication.data.voice.model.VoiceRoomX(active = true, createdAt = "", createdBy = vr.createdBy, id = vr.janusRoomId, janusRoomId = vr.janusRoomId, name = vr.name, roomCode = vr.name)
                                            voiceRooms.add(0, mapped)
                                            try { voiceRoomsAdapter.submitList(voiceRooms.toList()) } catch (_: Exception) {}
                                            try { dialog.dismiss() } catch (_: Exception) {}
                                        } else {
                                            val msg = res.exceptionOrNull()?.message ?: "Failed to create voice room"
                                            try { com.google.android.material.snackbar.Snackbar.make(requireView(), msg, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show() } catch (_: Exception) {}
                                        }
                                    } catch (e: Exception) {
                                        progressVoice?.visibility = View.GONE
                                        try { com.google.android.material.snackbar.Snackbar.make(requireView(), "Failed to create voice room: ${e.message}", com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show() } catch (_: Exception) {}
                                    } finally { setLoading(false) }
                                }
                            }
                            btnCancel.setOnClickListener { dialog.dismiss() }
                            dialog.show()
                        } catch (_: Exception) { try { com.google.android.material.snackbar.Snackbar.make(requireView(), "Failed to open create voice dialog", com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show() } catch (_: Exception) {} }
                        true
                    }
                    R.id.action_members -> {
                        // Navigate to members screen (pass group id)
                        try {
                            val args = Bundle().apply { putString("communityId", groupId) }
                            this@GroupDetailFragment.navigateWithDelay(R.id.action_localGroupDetailFragment_to_groupMembersFragment, args)
                        } catch (_: Exception) {
                            Toast.makeText(requireContext(), "Failed to open members", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    R.id.action_leave_community -> {
                        Toast.makeText(requireContext(), "Leave not supported for local groups", Toast.LENGTH_SHORT).show()
                        true
                    }
                    R.id.action_delete_community -> {
                        com.example.myapplication.ui.common.AppDialogHelper.showConfirmation(requireContext(), R.string.delete_confirm_title, R.string.delete_confirm_message, positiveRes = R.string.delete_confirm_yes, negativeRes = android.R.string.cancel, onPositive = { vm.deleteGroup() })
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
        // Observe invite result and show UI (Snackbar with Share/Copy)
        vm.inviteData.observe(viewLifecycleOwner) { data ->
            if (data == null) return@observe
            try {
                // Prefer full inviteLink, fallback to inviteCode
                val link = data.inviteLink.ifBlank { data.inviteCode }

                // Show dialog with the link and actions: Share / Copy / Close
                try {
                    com.example.myapplication.ui.common.AppDialogHelper.showInviteLinkDialog(requireContext(), link = link, onCopy = {
                        try {
                            val cb = requireContext().getSystemService(android.content.ClipboardManager::class.java)
                            val clip = android.content.ClipData.newPlainText("invite", link)
                            cb.setPrimaryClip(clip)
                            Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {}
                    }, onShare = {
                        try {
                            val send = android.content.Intent().apply {
                                action = android.content.Intent.ACTION_SEND
                                putExtra(android.content.Intent.EXTRA_TEXT, link)
                                type = "text/plain"
                            }
                            startActivity(android.content.Intent.createChooser(send, "Share invite"))
                        } catch (_: Exception) {}
                    })
                } catch (_: Exception) {
                    try { Toast.makeText(requireContext(), link, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                }

                // clear inviteData after handling
                try { vm.clearInviteData() } catch (_: Exception) {}
            } catch (_: Exception) {}
        }
    }

    private fun applyChatRoomsToggleState(ivToggle: ImageView?, rvChat: RecyclerView?, emptyRooms: View?) {
        rvChat?.visibility = if (chatRoomsExpanded && rvChat?.adapter?.itemCount ?: 0 > 0) View.VISIBLE else View.GONE
        val targetRotation = if (chatRoomsExpanded) 0f else 180f
        ivToggle?.let { ObjectAnimator.ofFloat(it, "rotation", targetRotation).apply { duration = 200; start() } }
    }

    private fun applyVoiceRoomsToggleState(ivToggle: ImageView?, rvVoice: RecyclerView?, emptyVoice: View?) {
        rvVoice?.visibility = if (voiceRoomsExpanded && rvVoice?.adapter?.itemCount ?: 0 > 0) View.VISIBLE else View.GONE
        val targetRotation = if (voiceRoomsExpanded) 0f else 180f
        ivToggle?.let { ObjectAnimator.ofFloat(it, "rotation", targetRotation).apply { duration = 200; start() } }
    }

    private fun loadVoiceRooms(serverRoomId: String, progressVoice: View?) {
        if (serverRoomId.isBlank()) return
        lifecycleScope.launch {
            try {
                progressVoice?.visibility = View.VISIBLE
                val res = withContext(Dispatchers.IO) { voiceRepo.getVoiceRooms(serverRoomId) }
                progressVoice?.visibility = View.GONE
                if (res.isSuccess) {
                    val list = res.getOrNull()?.voiceRooms.orEmpty()
                    voiceRooms.clear()
                    voiceRooms.addAll(list)
                    voiceRoomsAdapter.submitList(voiceRooms.toList())
                    try { view?.findViewById<View>(R.id.empty_voice_rooms_view)?.visibility = if (voiceRooms.isEmpty()) View.VISIBLE else View.GONE } catch (_: Exception) {}
                } else {
                    try { com.google.android.material.snackbar.Snackbar.make(requireView(), "Failed to load voice rooms: ${res.exceptionOrNull()?.message}", com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show() } catch (_: Exception) {}
                }
            } catch (_: Exception) {
                progressVoice?.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            if (this::defaultRoomsReceiver.isInitialized) {
                try { requireContext().unregisterReceiver(defaultRoomsReceiver) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}
