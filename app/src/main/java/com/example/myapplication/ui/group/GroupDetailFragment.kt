package com.example.myapplication.ui.group

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

class GroupDetailFragment : BaseFragment(R.layout.fragment_group_detail) {
    // Use activity-scoped VM so other fragments (members) can share the same instance
    private val vm: GroupDetailViewModel by activityViewModels()
    // ViewModel to manage chat rooms inside this local group
    private val roomsVm: GroupRoomViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Header back arrow (imageView id in layout)
        view.findViewById<View>(R.id.imageView)?.setOnClickListener { try { findNavController().navigateUp() } catch (_: Exception) {} }

        val tvUser = view.findViewById<TextView>(R.id.tvUsername)
        val grpImage = view.findViewById<ShapeableImageView>(R.id.grp_image)
        val grpName = view.findViewById<TextView>(R.id.grp_name)
        val memberCountTv = view.findViewById<TextView>(R.id.member_count_tv)
        val progress = view.findViewById<ProgressBar>(R.id.progress)
        val settingsAnchor = view.findViewById<ImageView>(R.id.setting_grp)
        val rvRooms = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_rooms)
        val emptyRoomsView = view.findViewById<View>(R.id.empty_rooms_view)
        val swipeRefresh = view.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)

        // Make marquee scroll without focus requirement (header username)
        tvUser?.isSelected = true

        // Load username into header
        lifecycleScope.launch {
            try {
                UserDataManager.getInstance(requireContext()).usernameFlow.collect { uname ->
                    if (uname != null && tvUser != null) tvUser.text = uname
                }
            } catch (_: Exception) {}
        }

        val groupId = arguments?.getString("communityId") ?: arguments?.getString("id")
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

        // observe VM state
        vm.loading.observe(viewLifecycleOwner) { loading ->
            progress?.visibility = if (loading) View.VISIBLE else View.GONE
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
                        rvRooms?.visibility = View.VISIBLE
                    }
                    roomsAdapter.submitList(mapped)
                }
            }
        }

        vm.toast.observe(viewLifecycleOwner) { msg ->
            msg?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
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

        settingsAnchor?.setOnClickListener { anchor ->
            // Show popup menu same as community detail
            val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
            try {
                popup.menuInflater.inflate(R.menu.menu_community_detail, popup.menu)
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
                                roomsVm.createChatRoom(effectiveParent, name) { success ->
                                    setLoading(false)
                                    if (success) {
                                        Toast.makeText(ctx, "Chat room created: $name", Toast.LENGTH_SHORT).show()
                                        roomsVm.loadChatRoomsForGroup(effectiveParent)
                                        try { dialog.dismiss() } catch (_: Exception) {}
                                    } else {
                                        try {
                                            com.google.android.material.snackbar.Snackbar.make(requireView(), "Failed to create chat room", com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
                                                .setAction("Retry") {
                                                    btnCreate.performClick()
                                                }.show()
                                        } catch (_: Exception) {
                                            Toast.makeText(ctx, "Failed to create chat room", Toast.LENGTH_SHORT).show()
                                        }
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
}
