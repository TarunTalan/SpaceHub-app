package com.example.myapplication.ui.community

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.widget.EditText
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
        val toolbar = view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        // show a back arrow and navigate up when clicked
        try {
            toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        } catch (_: Exception) {}

        // Inflate menu into fragment toolbar and handle clicks locally so icons appear in fragment bar
        try {
            toolbar.inflateMenu(R.menu.menu_community_detail)
            toolbar.setOnMenuItemClickListener { menuItem ->
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
                val isOwner = comm?.isOwner == true || (!comm?.creatorId.isNullOrBlank() && comm?.creatorId == currentEmail)
                toolbar.menu.findItem(R.id.action_delete_community)?.isVisible = isOwner
            }
        } catch (_: Exception) {}

        val rv = view.findViewById<RecyclerView>(R.id.rv_rooms)
        val progress = view.findViewById<ProgressBar>(R.id.progress)
        val memberCount = view.findViewById<TextView>(R.id.tv_member_count)
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

        vm.rooms.observe(viewLifecycleOwner) { list: List<DataRoom> -> adapter.submitList(list) }
        vm.totalMembers.observe(viewLifecycleOwner) { count -> memberCount.text = "Members: $count" }
        vm.loading.observe(viewLifecycleOwner) { show -> progress.visibility = if (show) View.VISIBLE else View.GONE }
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
}
