package com.example.myapplication.ui.community

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
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
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(R.id.rv_members)
        val progress = view.findViewById<ProgressBar>(R.id.progress)

        val communityId = arguments?.getString("communityId")
        if (communityId.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Missing communityId", Toast.LENGTH_SHORT).show()
            return
        }

        // By default, controls disabled; enable if current user is owner/admin
        var isAdmin = false
        lateinit var membersAdapter: MemberAdapter
        membersAdapter = MemberAdapter(
            isAdmin = isAdmin,
            onChangeRole = { member, next ->
                lifecycleScope.launch {
                    val res = CommunityRepository.getInstance(requireContext()).changeMemberRole(communityId, member.email, next)
                    if (res.isSuccess) loadMembers(communityId, rv, progress, membersAdapter) else Toast.makeText(requireContext(), "Failed", Toast.LENGTH_SHORT).show()
                }
            },
            onRemove = { member ->
                lifecycleScope.launch {
                    val res = CommunityRepository.getInstance(requireContext()).removeMember(communityId, member.email)
                    if (res.isSuccess) loadMembers(communityId, rv, progress, membersAdapter) else Toast.makeText(requireContext(), "Failed", Toast.LENGTH_SHORT).show()
                }
            }
        )
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = membersAdapter
        rv.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))

        // Determine admin rights from local community cache
        lifecycleScope.launch {
            val repo = CommunityRepository.getInstance(requireContext())
            val comm = repo.getCommunityById(communityId)
            val nowAdmin = (comm?.isOwner == true || comm?.isModerator == true)
            if (nowAdmin != isAdmin) {
                isAdmin = nowAdmin
                // Recreate adapter to reflect admin controls
                val current = (rv.adapter as? MemberAdapter)
                if (current != null) {
                    val currentList = current.currentList
                    membersAdapter = MemberAdapter(
                        isAdmin = isAdmin,
                        onChangeRole = { member, next ->
                            lifecycleScope.launch {
                                val res = CommunityRepository.getInstance(requireContext()).changeMemberRole(communityId, member.email, next)
                                if (res.isSuccess) loadMembers(communityId, rv, progress, membersAdapter) else Toast.makeText(requireContext(), "Failed", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onRemove = { member ->
                            lifecycleScope.launch {
                                val res = CommunityRepository.getInstance(requireContext()).removeMember(communityId, member.email)
                                if (res.isSuccess) loadMembers(communityId, rv, progress, membersAdapter) else Toast.makeText(requireContext(), "Failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    rv.adapter = membersAdapter
                    membersAdapter.submitList(currentList)
                }
            }
        }

        val swipeBgPaint = Paint().apply { color = Color.parseColor("#E53935") }
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
                val item = (rv.adapter as? MemberAdapter)?.currentList?.getOrNull(position)
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
                            if (res.isSuccess) {
                                loadMembers(communityId, rv, progress, membersAdapter)
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
                val fg = vh.itemView.findViewById<View>(R.id.fg_container)
                val tx = dX.coerceAtMost(0f)
                fg.translationX = tx

                // Draw red bg + delete icon on left swipe
                val itemView = vh.itemView
                if (dX < 0) {
                    val bg = RectF(itemView.right + dX, itemView.top.toFloat(), itemView.right.toFloat(), itemView.bottom.toFloat())
                    c.drawRect(bg, swipeBgPaint)
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

        loadMembers(communityId, rv, progress, membersAdapter)
    }

    private fun loadMembers(communityId: String, rv: RecyclerView, progress: ProgressBar, adapter: MemberAdapter) {
        progress.visibility = View.VISIBLE
        rv.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val repo = CommunityRepository.getInstance(requireContext())
            val res = repo.fetchMembers(communityId)
            progress.visibility = View.GONE
            rv.visibility = View.VISIBLE
            res.onSuccess { adapter.submitList(it) }
                .onFailure { Toast.makeText(requireContext(), it.message ?: "Failed", Toast.LENGTH_SHORT).show() }
        }
    }
}
