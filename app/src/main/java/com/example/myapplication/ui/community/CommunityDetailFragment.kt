package com.example.myapplication.ui.community

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.data.community.model.DataRoom
import com.example.myapplication.data.community.repository.CommunityRepository
import com.example.myapplication.data.user.UserDataManager
import com.example.myapplication.ui.community.adapter.MemberAdapter
import com.example.myapplication.ui.community.adapter.RoomAdapter
import com.example.myapplication.ui.community.viewmodel.CommunityDetailViewModel
import kotlinx.coroutines.launch
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class CommunityDetailFragment : Fragment(R.layout.fragment_community_detail) {

    private val vm: CommunityDetailViewModel by viewModels()
    private var isFirstResume = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        // show a back arrow and navigate up when clicked
        // toolbar may be absent in some layouts; guard usages
        toolbar?.let { tb ->
            try {
                tb.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
                tb.setNavigationOnClickListener { findNavController().navigateUp() }
            } catch (_: Exception) {}
        }

        // Inflate menu into fragment toolbar and handle clicks locally so icons appear in fragment bar
        toolbar?.let { tb ->
            try {
                tb.inflateMenu(R.menu.menu_community_detail)
                tb.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.action_edit_community -> {
                            val communityId = arguments?.getString("communityId")
                            if (!communityId.isNullOrBlank()) {
                                val args = Bundle().apply { putString("communityId", communityId) }
                                findNavController().navigate(R.id.action_communityDetail_to_editCommunity, args)
                            } else {
                                Toast.makeText(requireContext(), "Missing communityId", Toast.LENGTH_SHORT).show()
                            }
                            true
                        }
                        R.id.action_invite -> {
                            val communityId = arguments?.getString("communityId")
                            if (communityId.isNullOrBlank()) {
                                Toast.makeText(requireContext(), "Missing communityId", Toast.LENGTH_SHORT).show()
                                return@setOnMenuItemClickListener true
                            }
                            // Call repo to create link and show
                            viewLifecycleOwner.lifecycleScope.launch {
                                val repo = CommunityRepository.getInstance(requireContext())
                                val progress = ProgressBar(requireContext())
                                val dlg = AlertDialog.Builder(requireContext())
                                    .setTitle("Creating invite link...")
                                    .setView(progress)
                                    .setCancelable(false)
                                    .create()
                                try {
                                    dlg.show()
                                } catch (_: Exception) {}

                                val res = repo.createInviteLink(communityId)
                                try { dlg.dismiss() } catch (_: Exception) {}
                                res.onSuccess { data ->
                                    val link = data.inviteLink
                                    val msg = "Invite link:\n$link"
                                    AlertDialog.Builder(requireContext())
                                        .setTitle("Invite link")
                                        .setMessage(msg)
                                        .setPositiveButton("Copy") { d, _ ->
                                            copyToClipboard(requireContext(), link)
                                            Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
                                            d.dismiss()
                                        }
                                        .setNegativeButton("Share") { d, _ ->
                                            shareText(link)
                                            d.dismiss()
                                        }
                                        .setNeutralButton(android.R.string.ok, null)
                                        .show()
                                }.onFailure { e ->
                                    Toast.makeText(requireContext(), "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                            true
                        }
                        R.id.action_members -> {
                            val communityId = arguments?.getString("communityId")
                            if (communityId.isNullOrBlank()) {
                                Toast.makeText(requireContext(), "Missing communityId", Toast.LENGTH_SHORT).show()
                            } else {
                                findNavController().navigate(R.id.action_communityDetail_to_members, Bundle().apply { putString("communityId", communityId) })
                            }
                            true
                        }
                        R.id.action_leave_community -> {
                            val communityId = arguments?.getString("communityId")
                            if (communityId.isNullOrBlank()) {
                                Toast.makeText(requireContext(), "Missing communityId", Toast.LENGTH_SHORT).show()
                                return@setOnMenuItemClickListener true
                            }
                            AlertDialog.Builder(requireContext())
                                .setTitle("Leave Community")
                                .setMessage("Are you sure you want to leave this community?")
                                .setPositiveButton("Leave") { _, _ ->
                                    // We need communityName for API; get it from repo
                                    viewLifecycleOwner.lifecycleScope.launch {
                                        val repo = CommunityRepository.getInstance(requireContext())
                                        val comm = repo.getCommunityById(communityId)
                                        val name = comm?.name
                                        if (name.isNullOrBlank()) {
                                            Toast.makeText(requireContext(), "Community name missing", Toast.LENGTH_SHORT).show()
                                            return@launch
                                        }
                                        val res = repo.leaveCommunity(communityId, name)
                                        if (res.isSuccess) {
                                            Toast.makeText(requireContext(), "Left community", Toast.LENGTH_SHORT).show()
                                            findNavController().navigateUp()
                                        } else {
                                            Toast.makeText(requireContext(), "Failed to leave", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                            true
                        }
                        R.id.action_delete_community -> {
                            val ctx = requireContext()
                            AlertDialog.Builder(ctx)
                                .setTitle("Delete Community")
                                .setMessage("Are you sure you want to delete this community?")
                                .setPositiveButton("Delete") { _, _ -> vm.deleteCommunity() }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                            true
                        }
                        R.id.action_add_room -> {
                            val ctx = requireContext()
                            val input = EditText(ctx).apply { hint = "Room name" }
                            AlertDialog.Builder(ctx)
                                .setTitle("Add room")
                                .setView(input)
                                .setPositiveButton("Create") { d, _ ->
                                    val name = input.text?.toString()?.trim().orEmpty()
                                    if (name.isNotEmpty()) viewLifecycleOwner.lifecycleScope.launch { vm.createRoom(name) }
                                    else Toast.makeText(ctx, "Name required", Toast.LENGTH_SHORT).show()
                                    d.dismiss()
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                            true
                        }
                        else -> false
                    }
                }
            } catch (_: Exception) {}
        }

        // Also wire the settings ImageView as a popup anchor so users can open the same menu
        try {
            val settingsAnchor = view.findViewById<ImageView>(R.id.setting_community)
            settingsAnchor?.setOnClickListener { anchor ->
                val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
                popup.menuInflater.inflate(R.menu.menu_community_detail, popup.menu)
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_edit_community -> {
                            val communityId = arguments?.getString("communityId")
                            if (!communityId.isNullOrBlank()) {
                                val args = Bundle().apply { putString("communityId", communityId) }
                                findNavController().navigate(R.id.action_communityDetail_to_editCommunity, args)
                            } else {
                                Toast.makeText(requireContext(), "Missing communityId", Toast.LENGTH_SHORT).show()
                            }
                            true
                        }
                        R.id.action_invite -> {
                            val communityId = arguments?.getString("communityId")
                            if (communityId.isNullOrBlank()) {
                                Toast.makeText(requireContext(), "Missing communityId", Toast.LENGTH_SHORT).show()
                                return@setOnMenuItemClickListener true
                            }
                            viewLifecycleOwner.lifecycleScope.launch {
                                val repo = CommunityRepository.getInstance(requireContext())
                                val progress = ProgressBar(requireContext())
                                val dlg = AlertDialog.Builder(requireContext())
                                    .setTitle("Creating invite link...")
                                    .setView(progress)
                                    .setCancelable(false)
                                    .create()
                                try { dlg.show() } catch (_: Exception) {}

                                val res = repo.createInviteLink(communityId)
                                try { dlg.dismiss() } catch (_: Exception) {}
                                res.onSuccess { data ->
                                    val link = data.inviteLink
                                    val msg = "Invite link:\n$link"
                                    AlertDialog.Builder(requireContext())
                                        .setTitle("Invite link")
                                        .setMessage(msg)
                                        .setPositiveButton("Copy") { d, _ ->
                                            copyToClipboard(requireContext(), link)
                                            Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
                                            d.dismiss()
                                        }
                                        .setNegativeButton("Share") { d, _ ->
                                            shareText(link)
                                            d.dismiss()
                                        }
                                        .setNeutralButton(android.R.string.ok, null)
                                        .show()
                                }.onFailure { e ->
                                    Toast.makeText(requireContext(), "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                            true
                        }
                        R.id.action_members -> {
                            val communityId = arguments?.getString("communityId")
                            if (communityId.isNullOrBlank()) {
                                Toast.makeText(requireContext(), "Missing communityId", Toast.LENGTH_SHORT).show()
                            } else {
                                findNavController().navigate(R.id.action_communityDetail_to_members, Bundle().apply { putString("communityId", communityId) })
                            }
                            true
                        }
                        R.id.action_leave_community -> {
                            val communityId = arguments?.getString("communityId")
                            if (communityId.isNullOrBlank()) {
                                Toast.makeText(requireContext(), "Missing communityId", Toast.LENGTH_SHORT).show()
                                return@setOnMenuItemClickListener true
                            }
                            AlertDialog.Builder(requireContext())
                                .setTitle("Leave Community")
                                .setMessage("Are you sure you want to leave this community?")
                                .setPositiveButton("Leave") { _, _ ->
                                    viewLifecycleOwner.lifecycleScope.launch {
                                        val repo = CommunityRepository.getInstance(requireContext())
                                        val comm = repo.getCommunityById(communityId)
                                        val name = comm?.name
                                        if (name.isNullOrBlank()) {
                                            Toast.makeText(requireContext(), "Community name missing", Toast.LENGTH_SHORT).show()
                                            return@launch
                                        }
                                        val res = repo.leaveCommunity(communityId, name)
                                        if (res.isSuccess) {
                                            Toast.makeText(requireContext(), "Left community", Toast.LENGTH_SHORT).show()
                                            findNavController().navigateUp()
                                        } else {
                                            Toast.makeText(requireContext(), "Failed to leave", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                            true
                        }
                        R.id.action_delete_community -> {
                            val ctx = requireContext()
                            AlertDialog.Builder(ctx)
                                .setTitle("Delete Community")
                                .setMessage("Are you sure you want to delete this community?")
                                .setPositiveButton("Delete") { _, _ -> vm.deleteCommunity() }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                            true
                        }
                        R.id.action_add_room -> {
                            val ctx = requireContext()
                            val input = EditText(ctx).apply { hint = "Room name" }
                            AlertDialog.Builder(ctx)
                                .setTitle("Add room")
                                .setView(input)
                                .setPositiveButton("Create") { d, _ ->
                                    val name = input.text?.toString()?.trim().orEmpty()
                                    if (name.isNotEmpty()) viewLifecycleOwner.lifecycleScope.launch { vm.createRoom(name) }
                                    else Toast.makeText(ctx, "Name required", Toast.LENGTH_SHORT).show()
                                    d.dismiss()
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
            }
        } catch (_: Exception) {}

        val communityId = arguments?.getString("communityId")
        if (communityId.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Missing communityId", Toast.LENGTH_SHORT).show()
            return
        }
        vm.setCommunityId(communityId)

        // Hide delete menu item for non-owners by checking role from backend
        try {
            lifecycleScope.launch {
                val repo = CommunityRepository.getInstance(requireContext())
                val comm = repo.getCommunityById(communityId)
                // Use role from backend to determine ownership
                val role = comm?.role?.uppercase()
                val isOwner = role in listOf("OWNER", "CREATOR")
                // toolbar may be null in some layouts; use safe-call
                toolbar?.menu?.findItem(R.id.action_delete_community)?.isVisible = isOwner
            }
        } catch (_: Exception) {}

        val emptyView = view.findViewById<View>(R.id.empty_rooms_view)
        val rv = view.findViewById<RecyclerView>(R.id.rv_rooms)
        val memberCount = view.findViewById<TextView>(R.id.member_count_tv)
        val adminCount = view.findViewById<TextView>(R.id.admin_count_tv)
        val tvUser = view.findViewById<TextView>(R.id.tvUsername)
        // Make marquee scroll without focus requirement
        tvUser.isSelected = true
        val img = view.findViewById<ImageView>(R.id.community_image)
        val nameTv = view.findViewById<TextView>(R.id.community_name)

        // Back icon in header
        view.findViewById<View>(R.id.imageView)?.setOnClickListener { findNavController().navigateUp() }

        // Load username from DataStore
        viewLifecycleOwner.lifecycleScope.launch {
            UserDataManager.getInstance(requireContext()).usernameFlow.collect { uname ->
                tvUser.text = uname ?: tvUser.text
            }
        }

        // Populate community name, image, counts from local repo
        viewLifecycleOwner.lifecycleScope.launch {
            val repo = CommunityRepository.getInstance(requireContext())
            val c = repo.getCommunityById(communityId)
            c?.let { comm ->
                nameTv.text = comm.name
                // Member/admin counts if available
                if (comm.memberCount > 0) memberCount.text = comm.memberCount.toString()
                // Admin count not tracked locally; leave default 0 unless you have it
                // Load community profile image
                val url = comm.profilePicUrl
                if (!url.isNullOrBlank()) {
                    com.bumptech.glide.Glide.with(requireContext())
                        .load(url)
                        .placeholder(R.drawable.default_comm_icon)
                        .error(R.drawable.default_comm_icon)
                        .circleCrop()
                        .into(img)
                }
            }
        }

        val adapter = RoomAdapter(
            onClick = { room ->
                // Navigate to RoomFragment with room and community data
                viewLifecycleOwner.lifecycleScope.launch {
                    val repo = CommunityRepository.getInstance(requireContext())
                    val comm = repo.getCommunityById(communityId)

                    val bundle = Bundle().apply {
                        putString("communityId", communityId)
                        putString("roomId", room.id)
                        // Pass explicit roomCode when available to make lookup deterministic
                        putString("roomCode", room.roomCode)
                        putString("roomName", room.name)
                        putString("communityName", comm?.name ?: "Community")
                        putString("communityImageUrl", comm?.profilePicUrl)
                        putInt("memberCount", comm?.memberCount ?: 0)
                        putInt("adminCount", 0) // Update if you have admin count available
                    }

                    try {
                        findNavController().navigate(R.id.action_communityDetailFragment_to_roomFragment, bundle)
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Failed to open room: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onLongClick = { room ->
                val ctx = requireContext()
                AlertDialog.Builder(ctx)
                    .setTitle("Delete Room")
                    .setMessage("Are you sure you want to delete this room?")
                    .setPositiveButton("Delete") { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch { vm.deleteRoom(room.id) }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        )
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        // Ensure RecyclerView is above other views (fix overlay issues)
        try { rv.bringToFront(); rv.elevation = 12f } catch (_: Exception) {}

         vm.rooms.observe(viewLifecycleOwner) { list: List<DataRoom> ->
            adapter.submitList(list) {
                // run after list is committed to adapter
                val isEmpty = list.isEmpty()
                emptyView?.visibility = if (isEmpty) View.VISIBLE else View.GONE
                rv.visibility = if (isEmpty) View.GONE else View.VISIBLE
                if (!isEmpty) {
                    try { rv.post { rv.scrollToPosition(0) } } catch (_: Exception) {}
                 }
             }
        }
        vm.totalMembers.observe(viewLifecycleOwner) { count -> memberCount.text = count.toString() }
        vm.adminCount.observe(viewLifecycleOwner) { count -> adminCount.text = count.toString() }
        vm.toast.observe(viewLifecycleOwner) { msg -> if (!msg.isNullOrBlank()) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show() }
        vm.deleted.observe(viewLifecycleOwner) { deleted -> if (deleted == true) findNavController().popBackStack() }

        view.findViewById<View>(R.id.fab_create_room)?.setOnClickListener {
            val ctx = requireContext()
            val input = EditText(ctx)
            input.hint = "Room name"
            AlertDialog.Builder(ctx)
                .setTitle("Create Room")
                .setView(input)
                .setPositiveButton("Create") { d, _ ->
                    val name = input.text?.toString()?.trim().orEmpty()
                    if (name.isNotEmpty()) {
                        viewLifecycleOwner.lifecycleScope.launch { vm.createRoom(name) }
                    } else {
                        Toast.makeText(ctx, "Name required", Toast.LENGTH_SHORT).show()
                    }
                    d.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // Long-press member count to open Members list (quick entry point)
        view.findViewById<TextView>(R.id.members_tv)?.setOnLongClickListener {
            val communityId = arguments?.getString("communityId") ?: return@setOnLongClickListener true
            showMembersDialog(communityId)
            true
        }
        view.findViewById<TextView>(R.id.member_count_tv)?.setOnLongClickListener {
            val communityId = arguments?.getString("communityId") ?: return@setOnLongClickListener true
            showMembersDialog(communityId)
            true
        }

        // Wire swipe-to-refresh
        val swipe = view.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)
        swipe?.setOnRefreshListener { vm.refreshRooms() }
        vm.loading.observe(viewLifecycleOwner) { show ->
            // Do not toggle any full-screen loader; only stop the swipe spinner when loading completes
            if (!show) swipe?.isRefreshing = false
        }
    }

    private fun showMembersDialog(communityId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            val repo = CommunityRepository.getInstance(ctx)
            val progress = ProgressBar(ctx)
            val dlg = AlertDialog.Builder(ctx)
                .setTitle("Members")
                .setView(progress)
                .setCancelable(true)
                .create()
            try { dlg.show() } catch (_: Exception) {}

            val res = repo.fetchMembers(communityId)
            if (res.isFailure) {
                try { dlg.dismiss() } catch (_: Exception) {}
                Toast.makeText(ctx, res.exceptionOrNull()?.message ?: "Failed to load members", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val members = res.getOrNull().orEmpty()
            val currentEmail = UserDataManager.getInstance(ctx).getEmail()
            val currentIsAdmin = members.any { m ->
                m.email.equals(currentEmail, true) && (m.role.equals("ADMIN", true) || m.role.equals("OWNER", true))
            }

            val rv = RecyclerView(ctx)
            rv.layoutManager = LinearLayoutManager(ctx)
            rv.addItemDecoration(DividerItemDecoration(ctx, DividerItemDecoration.VERTICAL))

            val adapter = MemberAdapter(
                isAdmin = currentIsAdmin,
                onChangeRole = { member, newRole ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = repo.changeMemberRole(communityId, member.email, newRole)
                        result.onSuccess {
                            Toast.makeText(ctx, "Role updated", Toast.LENGTH_SHORT).show()
                            // refresh list
                            showMembersDialog(communityId)
                        }.onFailure { e ->
                            Toast.makeText(ctx, e.message ?: "Failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onRemove = { member ->
                    AlertDialog.Builder(ctx)
                        .setTitle("Remove member")
                        .setMessage("Remove ${member.username}?")
                        .setPositiveButton("Remove") { d, _ ->
                            d.dismiss()
                            viewLifecycleOwner.lifecycleScope.launch {
                                val result = repo.removeMember(communityId, member.email)
                                result.onSuccess {
                                    Toast.makeText(ctx, "Removed", Toast.LENGTH_SHORT).show()
                                    // refresh list
                                    showMembersDialog(communityId)
                                }.onFailure { e ->
                                    Toast.makeText(ctx, e.message ?: "Failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            )
            rv.adapter = adapter
            adapter.submitList(members)

            dlg.setView(rv)
        }
    }

    private fun copyToClipboard(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("invite_link", text))
    }

    private fun shareText(text: String) {
        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, null)
        startActivity(shareIntent)
    }

    override fun onResume() {
        super.onResume()
        // Only refresh on subsequent resumes (e.g., after returning from create room screen)
        // Skip the first resume to avoid duplicate API calls during initial load
        if (isFirstResume) {
            isFirstResume = false
        } else {
            vm.refreshRooms()
        }
    }
}
