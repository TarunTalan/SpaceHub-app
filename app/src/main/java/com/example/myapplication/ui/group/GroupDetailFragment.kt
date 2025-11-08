package com.example.myapplication.ui.group

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.example.myapplication.data.user.UserDataManager
import com.example.myapplication.ui.group.viewmodel.GroupDetailViewModel
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.launch

class GroupDetailFragment : Fragment(R.layout.fragment_group_detail) {
    // Use activity-scoped VM so other fragments (members) can share the same instance
    private val vm: GroupDetailViewModel by activityViewModels()

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

        vm.group.observe(viewLifecycleOwner) { data ->
            data?.let {
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
                    entry.savedStateHandle.set("refresh_local_groups", true)
                    entry.savedStateHandle.set("local_group_deleted_id", groupId)
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
                            findNavController().navigate(R.id.action_localGroupDetail_to_editGroup, args)
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "Failed to open editor: ${e.message}", Toast.LENGTH_SHORT).show()
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
                        // Not applicable to local groups
                        Toast.makeText(requireContext(), "Add room not applicable", Toast.LENGTH_SHORT).show()
                        true
                    }
                    R.id.action_members -> {
                        // Navigate to members screen (pass group id)
                        try {
                            val args = Bundle().apply { putString("communityId", groupId) }
                            findNavController().navigate(R.id.action_localGroupDetailFragment_to_groupMembersFragment, args)
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "Failed to open members: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    R.id.action_leave_community -> {
                        Toast.makeText(requireContext(), "Leave not supported for local groups", Toast.LENGTH_SHORT).show()
                        true
                    }
                    R.id.action_delete_community -> {
                        androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("Delete Group")
                            .setMessage("Are you sure you want to delete this group?")
                            .setPositiveButton("Delete") { _, _ -> vm.deleteGroup() }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
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
                val link = if (data.inviteLink.isNotBlank()) data.inviteLink else data.inviteCode

                // Show dialog with the link and actions: Share / Copy / Close
                try {
                    val builder = androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    builder.setTitle("Invite Link")
                    builder.setMessage(link)
                    builder.setPositiveButton("Share") { _, _ ->
                        try {
                            val send = android.content.Intent().apply {
                                action = android.content.Intent.ACTION_SEND
                                putExtra(android.content.Intent.EXTRA_TEXT, link)
                                type = "text/plain"
                            }
                            startActivity(android.content.Intent.createChooser(send, "Share invite"))
                        } catch (_: Exception) {}
                    }
                    builder.setNeutralButton("Copy") { _, _ ->
                        try {
                            val cb = requireContext().getSystemService(android.content.ClipboardManager::class.java)
                            val clip = android.content.ClipData.newPlainText("invite", link)
                            cb.setPrimaryClip(clip)
                            Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {}
                    }
                    builder.setNegativeButton(android.R.string.cancel, null)
                    builder.show()
                } catch (_: Exception) {
                    // fallback: show toast and copy
                    try { Toast.makeText(requireContext(), link, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                }

                // clear inviteData after handling
                try { vm.clearInviteData() } catch (_: Exception) {}
            } catch (_: Exception) {}
        }
    }
}
