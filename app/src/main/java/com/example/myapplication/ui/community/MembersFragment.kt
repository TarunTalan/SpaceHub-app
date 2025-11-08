package com.example.myapplication.ui.community

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.data.community.repository.CommunityRepository
import com.example.myapplication.ui.community.adapter.MemberAdapter
import kotlinx.coroutines.launch

class MembersFragment : Fragment(R.layout.fragment_members) {
    private var isAdmin = false
    private lateinit var membersAdapter: MemberAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(R.id.rv_members)
        val progress = view.findViewById<ProgressBar>(R.id.progress)

        val communityId = arguments?.getString("communityId")
        if (communityId.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Missing communityId", Toast.LENGTH_SHORT).show()
            return
        }

        // Setup RecyclerView
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))

        // Initialize adapter with helper function
        fun createAdapter() = MemberAdapter(
            isAdmin = isAdmin,
            onChangeRole = { member, next ->
                lifecycleScope.launch {
                    val res = CommunityRepository.getInstance(requireContext()).changeMemberRole(communityId, member.email, next)
                    if (res.isSuccess) {
                        // Reload members after role change using captured RecyclerView + ProgressBar
                        loadMembers(communityId, rv, progress)
                    } else {
                        Toast.makeText(requireContext(), "Failed", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onRemove = { member ->
                lifecycleScope.launch {
                    val res = CommunityRepository.getInstance(requireContext()).removeMember(communityId, member.email)
                    if (res.isSuccess) {
                        // Reload members after removal using captured RecyclerView + ProgressBar
                        loadMembers(communityId, rv, progress)
                    } else {
                        Toast.makeText(requireContext(), "Failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )

        // Determine admin rights from local community cache, then setup adapter
        lifecycleScope.launch {
            val repo = CommunityRepository.getInstance(requireContext())
            val comm = repo.getCommunityById(communityId)
            // Use role from backend to determine admin status
            val role = comm?.role?.uppercase()
            isAdmin = role in listOf("OWNER", "CREATOR", "ADMIN", "MODERATOR", "MANAGER")
            android.util.Log.d("MembersFragment", "Admin status: role=$role, isAdmin=$isAdmin")

            // Now create and set adapter with correct admin status
            membersAdapter = createAdapter()
            rv.adapter = membersAdapter

            // Setup swipe-to-delete
            setupSwipeToDelete(rv, communityId)

            // Load members
            loadMembers(communityId, rv, progress)
        }
    }

    private fun setupSwipeToDelete(rv: RecyclerView, communityId: String) {
        val deleteIcon = ResourcesCompat.getDrawable(resources, android.R.drawable.ic_menu_delete, null)

        // Attach swipe-to-confirm-delete helper (no reveal button)
        val touchHelper = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val position = vh.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return

                if (!isAdmin) {
                    Toast.makeText(requireContext(), "Only admins can remove members", Toast.LENGTH_SHORT).show()
                    rv.adapter?.notifyItemChanged(position)
                    return
                }
                val item = membersAdapter.currentList.getOrNull(position)
                val email = item?.email
                if (email.isNullOrBlank()) {
                    Toast.makeText(requireContext(), "Unknown member", Toast.LENGTH_SHORT).show()
                    rv.adapter?.notifyItemChanged(position)
                    return
                }
                AlertDialog.Builder(requireContext())
                    .setTitle("Remove member")
                    .setMessage("Remove ${item.username ?: email}?")
                    .setPositiveButton("Remove") { _, _ ->
                        lifecycleScope.launch {
                            val res = CommunityRepository.getInstance(requireContext()).removeMember(communityId, email)
                            val progress = view?.findViewById<ProgressBar>(R.id.progress)
                            if (res.isSuccess && progress != null) {
                                loadMembers(communityId, rv, progress)
                            } else {
                                Toast.makeText(requireContext(), "Failed", Toast.LENGTH_SHORT).show()
                                rv.adapter?.notifyItemChanged(position)
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        rv.adapter?.notifyItemChanged(position)
                    }
                    .setOnCancelListener { rv.adapter?.notifyItemChanged(position) }
                    .show()
            }
            override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
                // Foreground container (present in item_member layout)
                val fg = vh.itemView.findViewById<View>(R.id.fg_container)
                val tx = dX.coerceAtMost(0f)
                fg?.translationX = tx

                // Draw red bg + delete icon on left swipe
                val itemView = vh.itemView
                if (dX < 0) {
                    val bg = RectF(itemView.right + dX, itemView.top.toFloat(), itemView.right.toFloat(), itemView.bottom.toFloat())
                    val paint = android.graphics.Paint().apply { color = "#E53935".toColorInt() }
                    c.drawRect(bg, paint)
                    deleteIcon?.let { icon ->
                        val iconMargin = (itemView.height - 48) / 2
                        val iconSize = 48
                        val top = itemView.top + iconMargin
                        val right = itemView.right - iconMargin
                        icon.setBounds(right - iconSize, top, right, top + iconSize)
                        DrawableCompat.setTint(icon, Color.WHITE)
                        icon.draw(c)
                    }
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(touchHelper).attachToRecyclerView(rv)
    }

    private fun loadMembers(communityId: String, rv: RecyclerView, progress: ProgressBar) {
        progress.visibility = View.VISIBLE
        rv.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val repo = CommunityRepository.getInstance(requireContext())
            val res = repo.fetchMembers(communityId)
            progress.visibility = View.GONE

            res.onSuccess { members ->
                android.util.Log.d("MembersFragment", "Members loaded successfully: ${members.size} members")
                rv.visibility = View.VISIBLE
                membersAdapter.submitList(members) {
                    // Callback after list is submitted
                    android.util.Log.d("MembersFragment", "Adapter updated with ${membersAdapter.itemCount} items")
                    if (members.isEmpty()) {
                        Toast.makeText(requireContext(), "No members found", Toast.LENGTH_SHORT).show()
                    }
                }
            }.onFailure { error ->
                android.util.Log.e("MembersFragment", "Failed to load members: ${error.message}", error)
                rv.visibility = View.VISIBLE // Show empty RecyclerView
                Toast.makeText(requireContext(), "Failed: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
