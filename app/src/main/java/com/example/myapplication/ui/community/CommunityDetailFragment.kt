package com.example.myapplication.ui.community

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.data.community.model.DataRoom
import com.example.myapplication.data.community.repository.CommunityRepository
import com.example.myapplication.data.user.UserDataManager
import com.example.myapplication.ui.community.adapter.RoomAdapter
import com.example.myapplication.ui.community.viewmodel.CommunityDetailViewModel
import kotlinx.coroutines.launch

class CommunityDetailFragment : Fragment(R.layout.fragment_community_detail) {

    private val vm: CommunityDetailViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Ensure rooms are refreshed when fragment becomes visible
        // (useful after returning from create screen)
        viewLifecycleOwner.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                super.onResume(owner)
                vm.refreshRooms()
            }
        })

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
                                            copyToClipboard(requireContext(), "invite_link", link)
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
                                            copyToClipboard(requireContext(), "invite_link", link)
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

        // Hide delete menu item for non-admins by checking local community record
        try {
            lifecycleScope.launch {
                val repo = CommunityRepository.getInstance(requireContext())
                val comm = repo.getCommunityById(communityId)
                val currentEmail = UserDataManager.getInstance(requireContext()).getEmail()
                val isOwner = comm?.let { c -> (c.isOwner == true) || (!c.creatorId.isNullOrBlank() && c.creatorId == currentEmail) } == true
                // toolbar may be null in some layouts; use safe-call
                toolbar?.menu?.findItem(R.id.action_delete_community)?.isVisible = isOwner
            }
        } catch (_: Exception) {}

        val emptyView = view.findViewById<View>(R.id.empty_rooms_view)
        val rv = view.findViewById<RecyclerView>(R.id.rv_rooms)
        val progress = view.findViewById<ProgressBar>(R.id.progress)
        val memberCount = view.findViewById<TextView>(R.id.member_count_tv)
        val adminCount = view.findViewById<TextView>(R.id.admin_count_tv)
        val tvUser = view.findViewById<TextView>(R.id.tvUsername)
        // Make marquee scroll without focus requirement
        tvUser.isSelected = true
        val img = view.findViewById<ImageView>(R.id.community_image)
        val nameTv = view.findViewById<TextView>(R.id.community_name)

        view.findViewById<View>(R.id.refresh_rooms)?.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { vm.loadRooms() }
        }

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
                val ctx = requireContext()
                val input = EditText(ctx)
                input.hint = "New room name"
                AlertDialog.Builder(ctx)
                    .setTitle("Rename Room")
                    .setView(input)
                    .setPositiveButton("Rename") { d: DialogInterface, _ ->
                        val newName = input.text?.toString()?.trim().orEmpty()
                        if (newName.isNotEmpty()) {
                            viewLifecycleOwner.lifecycleScope.launch { vm.renameRoom(room.id, newName) }
                        } else {
                            Toast.makeText(ctx, "Name required", Toast.LENGTH_SHORT).show()
                        }
                        d.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
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
            // debug log to confirm emission
            try { Log.d("CommunityDetail", "rooms observed size=${list.size} first=${list.firstOrNull()}") } catch (_: Exception) {}

            adapter.submitList(list) {
                // run after list is committed to adapter
                val isEmpty = list.isEmpty()
                emptyView?.visibility = if (isEmpty) View.VISIBLE else View.GONE
                rv.visibility = if (isEmpty) View.GONE else View.VISIBLE
                if (!isEmpty) {
                    try { rv.post { rv.scrollToPosition(0) } } catch (_: Exception) {}
                    // DEBUG: force full refresh in case DiffUtil incorrectly thinks list unchanged
                    try { adapter.notifyDataSetChanged() } catch (_: Exception) {}
                    try { android.util.Log.d("CommunityDetail", "adapter.itemCount=${adapter.itemCount}") } catch (_: Exception) {}
                    // DEBUG: inspect RecyclerView layout bounds and make it visible with a tint
                    try {
                        rv.setBackgroundColor(Color.parseColor("#33FF00")) // translucent green
                        rv.post {
                            try {
                                val rect = Rect()
                                rv.getGlobalVisibleRect(rect)
                                android.util.Log.d("CommunityDetail", "RV bounds: top=${rv.top}, left=${rv.left}, width=${rv.width}, height=${rv.height}, visibleRect=$rect")
                            } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                 }
             }
        }
        vm.totalMembers.observe(viewLifecycleOwner) { count -> memberCount.text = count.toString() }
        vm.loading.observe(viewLifecycleOwner) { show ->
            progress.visibility = if (show) View.VISIBLE else View.GONE
        }
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
    }

    private fun copyToClipboard(ctx: Context, label: String, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
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
}
