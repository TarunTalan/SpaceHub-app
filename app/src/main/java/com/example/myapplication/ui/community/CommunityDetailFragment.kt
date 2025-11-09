package com.example.myapplication.ui.community

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Resolve communityId once for the fragment - required for most actions
        val communityId = arguments?.getString("communityId")
        if (communityId.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Missing communityId", Toast.LENGTH_SHORT).show()
            return
        }

        val toolbar = view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)

        // Inform ViewModel about the current community and refresh details immediately
        try {
            vm.setCommunityId(communityId)
            vm.refreshDetails()
        } catch (_: Exception) {}

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
                            // Use outer communityId
                            val args = Bundle().apply { putString("communityId", communityId) }
                            findNavController().navigate(R.id.action_communityDetail_to_editCommunity, args)
                            true
                        }
                        R.id.action_invite -> {
                            // Use outer communityId
                            viewLifecycleOwner.lifecycleScope.launch {
                                val repo = CommunityRepository.getInstance(requireContext())
                                val dlg = com.example.myapplication.ui.common.AppDialogHelper.createProgressDialog(requireContext(), title = "Creating invite link...", cancelable = false)
                                try { dlg.show() } catch (_: Exception) {}

                                val res = repo.createInviteLink(communityId)
                                try { dlg.dismiss() } catch (_: Exception) {}
                                res.onSuccess { data ->
                                    val link = data.inviteLink
                                    com.example.myapplication.ui.common.AppDialogHelper.showInviteLinkDialog(requireContext(), link = link, onCopy = {
                                        copyToClipboard(requireContext(), link)
                                        Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
                                    }, onShare = { shareText(link) })
                                }.onFailure { e ->
                                    Toast.makeText(requireContext(), "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                            true
                        }
                        R.id.action_members -> {
                            // Use outer communityId
                            findNavController().navigate(R.id.action_communityDetail_to_members, Bundle().apply { putString("communityId", communityId) })
                            true
                        }
                        R.id.action_leave_community -> {
                            com.example.myapplication.ui.common.AppDialogHelper.showConfirmation(
                                requireContext(),
                                R.string.leave_confirm_title,
                                R.string.leave_confirm_message,
                                positiveRes = R.string.leave_confirm_yes,
                                negativeRes = android.R.string.cancel,
                                onPositive = {
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
                            )
                             true
                        }
                        R.id.action_delete_community -> {
                            com.example.myapplication.ui.common.AppDialogHelper.showConfirmation(requireContext(), R.string.delete_confirm_title, R.string.delete_confirm_message, positiveRes = R.string.delete_confirm_yes, negativeRes = android.R.string.cancel, onPositive = { vm.deleteCommunity() })
                             true
                        }
                        R.id.action_add_room -> {
                            try {
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
                                            // Call VM to create room (VM handles API and DB updates)
                                            vm.createRoom(name)
                                            Toast.makeText(requireContext(), "Chat room created: $name", Toast.LENGTH_SHORT).show()
                                            dialog.dismiss()
                                        } catch (e: Exception) {
                                            com.google.android.material.snackbar.Snackbar.make(requireView(), "Failed to create room: ${e.message}", com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
                                                .setAction("Retry") { btnCreate.performClick() }
                                                .show()
                                        } finally {
                                            setLoading(false)
                                        }
                                    }
                                }
                                btnCancel.setOnClickListener { dialog.dismiss() }
                                dialog.show()
                            } catch (_: Exception) {
                                try { Toast.makeText(requireContext(), "Failed to open dialog", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                            }
                            true
                        }
                        else -> false
                    }
                }
            } catch (_: Exception) {}
        }

        // Ensure settings icon (ImageView) remains visible and wired to show the same popup menu
        try {
            val settingsAnchor = view.findViewById<ImageView>(R.id.setting_community)
            settingsAnchor?.visibility = View.VISIBLE
            settingsAnchor?.setOnClickListener { anchor ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val repo = CommunityRepository.getInstance(requireContext())
                    val comm = repo.getCommunityById(communityId)
                    val email = UserDataManager.getInstance(requireContext()).getEmail()
                    val role = comm?.role?.uppercase()
                    val adminRoles = setOf("OWNER", "CREATOR", "ADMIN", "MODERATOR", "MANAGER")
                    val isOwner = (comm?.isOwner == true) || (!comm?.creatorId.isNullOrBlank() && comm?.creatorId.equals(email, true)) || (role in listOf("OWNER", "CREATOR"))
                    val isAdmin = isOwner || (role != null && adminRoles.any { role.contains(it) }) || (comm?.isModerator == true)

                    val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
                    popup.menuInflater.inflate(R.menu.menu_community_detail, popup.menu)
                    // Hide admin-only items from non-admins
                    popup.menu.findItem(R.id.action_delete_community)?.isVisible = isAdmin
                    popup.menu.findItem(R.id.action_add_room)?.isVisible = isAdmin
                    popup.menu.findItem(R.id.action_edit_community)?.isVisible = isAdmin

                    popup.setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            R.id.action_edit_community -> {
                                if (!isAdmin) { Toast.makeText(requireContext(), "Only admins can edit", Toast.LENGTH_SHORT).show(); return@setOnMenuItemClickListener true }
                                val args = Bundle().apply { putString("communityId", communityId) }
                                findNavController().navigate(R.id.action_communityDetail_to_editCommunity, args)
                                true
                            }
                            R.id.action_invite -> {
                                viewLifecycleOwner.lifecycleScope.launch {
                                    val dlg = com.example.myapplication.ui.common.AppDialogHelper.createProgressDialog(requireContext(), title = "Creating invite link...", cancelable = false)
                                    try { dlg.show() } catch (_: Exception) {}
                                    val res = repo.createInviteLink(communityId)
                                    try { dlg.dismiss() } catch (_: Exception) {}
                                    res.onSuccess { data -> com.example.myapplication.ui.common.AppDialogHelper.showInviteLinkDialog(requireContext(), link = data.inviteLink, onCopy = { copyToClipboard(requireContext(), data.inviteLink) }, onShare = { shareText(data.inviteLink) }) }
                                    res.onFailure { e -> Toast.makeText(requireContext(), "Failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                                }
                                true
                            }
                            R.id.action_members -> {
                                findNavController().navigate(R.id.action_communityDetail_to_members, Bundle().apply { putString("communityId", communityId) })
                                true
                            }
                            R.id.action_leave_community -> {
                                com.example.myapplication.ui.common.AppDialogHelper.showConfirmation(requireContext(), R.string.leave_confirm_title, R.string.leave_confirm_message, positiveRes = R.string.leave_confirm_yes, negativeRes = android.R.string.cancel, onPositive = {
                                    viewLifecycleOwner.lifecycleScope.launch {
                                        val res = CommunityRepository.getInstance(requireContext()).leaveCommunity(communityId, repo.getCommunityById(communityId)?.name ?: "")
                                        if (res.isSuccess) findNavController().navigateUp() else Toast.makeText(requireContext(), "Failed to leave", Toast.LENGTH_SHORT).show()
                                    }
                                })
                                true
                            }
                            R.id.action_delete_community -> {
                                if (!isAdmin) { Toast.makeText(requireContext(), "Only admins can delete community", Toast.LENGTH_SHORT).show(); return@setOnMenuItemClickListener true }
                                com.example.myapplication.ui.common.AppDialogHelper.showConfirmation(requireContext(), R.string.delete_confirm_title, R.string.delete_confirm_message, positiveRes = R.string.delete_confirm_yes, negativeRes = android.R.string.cancel, onPositive = { vm.deleteCommunity() })
                                true
                            }
                            R.id.action_add_room -> {
                                if (!isAdmin) { Toast.makeText(requireContext(), "Only admins can add rooms", Toast.LENGTH_SHORT).show(); return@setOnMenuItemClickListener true }
                                // show same dialog as toolbar handler
                                try {
                                    val inflater = layoutInflater
                                    val dialogView = inflater.inflate(R.layout.dialog_create_chat_room, null)
                                    val etName = dialogView.findViewById<EditText>(R.id.et_room_name)
                                    val tvError = dialogView.findViewById<TextView>(R.id.dialog_error)
                                    val btnCreate = dialogView.findViewById<android.widget.Button>(R.id.btn_create)
                                    val btnCancel = dialogView.findViewById<android.widget.Button>(R.id.btn_cancel)

                                    val dialog = try { com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setView(dialogView).create() } catch (_: Exception) { com.example.myapplication.ui.common.AppDialogHelper.createViewDialog(requireContext(), title = null, customView = dialogView, positiveText = null, negativeText = null, cancelable = true) }

                                    fun setLoading(loading: Boolean) { btnCreate.isEnabled = !loading; btnCancel.isEnabled = !loading; etName.isEnabled = !loading }
                                    btnCreate.setOnClickListener {
                                        val name = etName.text?.toString()?.trim().orEmpty()
                                        if (name.isEmpty()) { tvError.text = "Name is required"; tvError.visibility = View.VISIBLE; return@setOnClickListener }
                                        tvError.visibility = View.GONE
                                        setLoading(true)
                                        viewLifecycleOwner.lifecycleScope.launch {
                                            try { vm.createRoom(name); Toast.makeText(requireContext(), "Chat room created: $name", Toast.LENGTH_SHORT).show(); dialog.dismiss() } catch (e: Exception) { com.google.android.material.snackbar.Snackbar.make(requireView(), "Failed to create room: ${e.message}", com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE).setAction("Retry") { btnCreate.performClick() }.show() } finally { setLoading(false) }
                                        }
                                    }
                                    btnCancel.setOnClickListener { dialog.dismiss() }
                                    dialog.show()
                                } catch (_: Exception) { Toast.makeText(requireContext(), "Failed to open dialog", Toast.LENGTH_SHORT).show() }
                                true
                            }
                            else -> false
                        }
                    }
                    popup.show()
                }
            }
        } catch (_: Exception) {}

        // Hide delete/add/edit menu items for non-admins. Use communityId from arguments to avoid scope issues.
        try {
            val adminRoles = setOf("OWNER", "CREATOR", "ADMIN", "MODERATOR", "MANAGER")
            lifecycleScope.launch {
                val cid = arguments?.getString("communityId")
                if (cid.isNullOrBlank()) return@launch
                val repo = CommunityRepository.getInstance(requireContext())
                val comm = repo.getCommunityById(cid)
                val email = com.example.myapplication.data.user.UserDataManager.getInstance(requireContext()).getEmail()
                // Determine ownership/admin status robustly: prefer explicit flag, fallback to creatorId or role
                val role = comm?.role?.uppercase()
                val isOwner = (comm?.isOwner == true) || (!comm?.creatorId.isNullOrBlank() && comm?.creatorId.equals(email, true)) || (role in listOf("OWNER", "CREATOR"))
                val isAdmin = isOwner || (role != null && adminRoles.any { role.contains(it) }) || (comm?.isModerator == true)
                // toolbar may be null in some layouts; use safe-call
                toolbar?.menu?.findItem(R.id.action_delete_community)?.isVisible = isAdmin
                toolbar?.menu?.findItem(R.id.action_add_room)?.isVisible = isAdmin
                toolbar?.menu?.findItem(R.id.action_edit_community)?.isVisible = isAdmin
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
                        putInt("adminCount", 0)
                    }

                    try {
                        findNavController().navigate(R.id.action_communityDetailFragment_to_roomFragment, bundle)
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Failed to open room: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onLongClick = { room ->
                // Only allow room deletion for admins/owners. Check local community flags first as a fast-path.
                viewLifecycleOwner.lifecycleScope.launch {
                    val repo = CommunityRepository.getInstance(requireContext())
                    val comm = repo.getCommunityById(communityId)
                    val email = UserDataManager.getInstance(requireContext()).getEmail()
                    val role = comm?.role?.uppercase()
                    val adminRoles = setOf("OWNER", "CREATOR", "ADMIN", "MODERATOR", "MANAGER")
                    val isOwner = comm?.isOwner == true || (!comm?.creatorId.isNullOrBlank() && comm?.creatorId.equals(email, true)) || (role in listOf("OWNER", "CREATOR"))
                    val isAdmin = isOwner || (role != null && adminRoles.any { role.contains(it) }) || (comm?.isModerator == true)

                    if (!isAdmin) {
                        return@launch
                    }

                    val ctx = requireContext()
                    com.example.myapplication.ui.common.AppDialogHelper.showConfirmation(ctx, "Delete Room", "Are you sure you want to delete this room?", positiveText = "Delete", negativeText = ctx.getString(android.R.string.cancel), onPositive = {
                        viewLifecycleOwner.lifecycleScope.launch { vm.deleteRoom(room.id) }
                    })
                }
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
            val input = EditText(ctx).apply { hint = "Room name" }
            val dlg = com.example.myapplication.ui.common.AppDialogHelper.createViewDialog(ctx, title = "Create Room", customView = input, positiveText = "Create", negativeText = ctx.getString(android.R.string.cancel), onPositive = {
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        vm.createRoom(name)
                    }
                } else {
                    Toast.makeText(ctx, "Name required", Toast.LENGTH_SHORT).show()
                }
            }, cancelable = true)
            try { dlg.show() } catch (_: Exception) {}
        }

        // Long-press member count to open Members list (quick entry point)
        view.findViewById<TextView>(R.id.members_tv)?.setOnLongClickListener {
            showMembersDialog(communityId)
            true
        }
        view.findViewById<TextView>(R.id.member_count_tv)?.setOnLongClickListener {
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

             // Force a fresh network fetch for members when user explicitly opens Members
             val initialRes = repo.fetchMembers(communityId, force = true)

             val progressDlg = com.example.myapplication.ui.common.AppDialogHelper.createProgressDialog(ctx, title = "Members", cancelable = true)
             try { progressDlg.show() } catch (_: Exception) {}

             if (initialRes.isFailure) {
                 Toast.makeText(ctx, initialRes.exceptionOrNull()?.message ?: "Failed to load members", Toast.LENGTH_SHORT).show()
                 return@launch
             }

             val initialMembers = initialRes.getOrNull().orEmpty()
             val currentEmail = UserDataManager.getInstance(ctx).getEmail()
             val currentIsAdmin = initialMembers.any { m -> m.email.equals(currentEmail, true) && (m.role.equals("ADMIN", true) || m.role.equals("OWNER", true)) }

             // Build dialog with a single RecyclerView + adapter. We'll update adapter.submitList() in-place.
             val rv = RecyclerView(ctx).apply {
                 layoutManager = LinearLayoutManager(ctx)
                 addItemDecoration(DividerItemDecoration(ctx, DividerItemDecoration.VERTICAL))
             }

             // Adapter instance (mutable) so callbacks can update its list. Declare first so lambdas can reference it.
             lateinit var membersAdapterLocal: MemberAdapter
             membersAdapterLocal = MemberAdapter(
                 currentIsAdmin,
                 currentEmail,
                 onChangeRole = { member, newRole ->
                     viewLifecycleOwner.lifecycleScope.launch {
                         val res = repo.changeMemberRole(communityId, member.email, newRole)
                         if (res.isSuccess) {
                             Toast.makeText(ctx, "Role updated", Toast.LENGTH_SHORT).show()
                             val refreshed = repo.fetchMembers(communityId, force = true).getOrDefault(emptyList())
                             membersAdapterLocal.submitList(refreshed)
                             try { vm.refreshDetails() } catch (_: Exception) {}
                         } else {
                             Toast.makeText(ctx, res.exceptionOrNull()?.message ?: "Failed to update role", Toast.LENGTH_SHORT).show()
                         }
                     }
                 },
                 onRemove = { member ->
                     com.example.myapplication.ui.common.AppDialogHelper.showConfirmation(ctx, "Remove member", "Remove ${member.username ?: member.email}?", positiveText = "Remove", negativeText = ctx.getString(android.R.string.cancel), onPositive = {
                         viewLifecycleOwner.lifecycleScope.launch {
                             val result = repo.removeMemberAndRefresh(communityId, member.email)
                             if (result.isSuccess) {
                                 Toast.makeText(ctx, "Removed", Toast.LENGTH_SHORT).show()
                                 val refreshed = repo.fetchMembers(communityId, force = true).getOrDefault(emptyList())
                                 membersAdapterLocal.submitList(refreshed)
                                 try { vm.refreshDetails() } catch (_: Exception) {}
                             } else {
                                 Toast.makeText(ctx, result.exceptionOrNull()?.message ?: "Failed to remove", Toast.LENGTH_SHORT).show()
                             }
                         }
                     })
                 }
             )

             rv.adapter = membersAdapterLocal
             membersAdapterLocal.submitList(initialMembers)

             // Show single dialog and keep it alive while we update the adapter in-place
             val listDlg = com.example.myapplication.ui.common.AppDialogHelper.createViewDialog(
                 ctx,
                 title = "Members",
                 customView = rv,
                 positiveText = null,
                 negativeText = ctx.getString(android.R.string.cancel),
                 cancelable = true
             )
             try { listDlg.show() } catch (_: Exception) {}
         }
     }

    private fun copyToClipboard(ctx: Context, text: String) {
         try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("invite_link", text))
            Toast.makeText(ctx, "Copied to clipboard", Toast.LENGTH_SHORT).show()
         } catch (_: Exception) {}
     }

     private fun shareText(text: String) {
         try {
             val sendIntent: Intent = Intent().apply {
                 action = Intent.ACTION_SEND
                 putExtra(Intent.EXTRA_TEXT, text)
                 type = "text/plain"
             }
             val shareIntent = Intent.createChooser(sendIntent, null)
             startActivity(shareIntent)
         } catch (_: Exception) {}
     }

}
