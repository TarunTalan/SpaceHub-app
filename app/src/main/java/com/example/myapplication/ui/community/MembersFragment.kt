package com.example.myapplication.ui.community

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import com.example.myapplication.R
import com.example.myapplication.data.community.repository.CommunityRepository
import com.example.myapplication.data.user.UserDataManager
import com.example.myapplication.ui.community.adapter.MemberAdapter

class MembersFragment : Fragment(R.layout.fragment_members) {
    private var isAdmin = false
    private var currentEmail: String? = null
    private lateinit var membersAdapter: MemberAdapter
    // Keep a reference to the attached ItemTouchHelper so we can detach it when user is not admin
    private var swipeHelper: ItemTouchHelper? = null

    // Define admin roles centrally
    private val adminRoles = setOf("OWNER", "Admin", "ADMIN")

    // Determine admin status from members list and cached currentEmail
    private fun computeIsAdminFromMembers(
        members: List<com.example.myapplication.data.community.model.Member>,
        email: String?
    ): Boolean {
        if (email.isNullOrBlank()) return false
        return members.any { m ->
            val roleUpper = m.role?.trim()?.uppercase()
            roleUpper != null && adminRoles.contains(roleUpper) && m.email.equals(email, ignoreCase = true)
        }
    }

    // Create a MemberAdapter bound to the provided UI/context pieces. This is a class-level helper
    // so other lifecycle methods (e.g., loadMembers) can recreate the adapter when admin flag changes.
    private fun createAdapter(communityId: String, rv: RecyclerView, progress: ProgressBar): MemberAdapter {
        return MemberAdapter(
            isAdmin,
            currentEmail,
            { member: com.example.myapplication.data.community.model.Member, next: String ->
                lifecycleScope.launch {
                    val email = member.email ?: ""
                    val res =
                        CommunityRepository.getInstance(requireContext()).changeMemberRole(communityId, email, next)
                    if (res.isSuccess) {
                        loadMembers(communityId, rv, progress)
                    } else {
                        Toast.makeText(requireContext(), "Failed to change role", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            { member: com.example.myapplication.data.community.model.Member ->
                lifecycleScope.launch {
                    val repo = CommunityRepository.getInstance(requireContext())
                    val email = member.email ?: ""
                    val res = repo.removeMemberAndRefresh(communityId, email)
                    if (res.isSuccess) {
                        // Reload members after removal using captured RecyclerView + ProgressBar (force fresh)
                        loadMembers(communityId, rv, progress)
                        Toast.makeText(requireContext(), "Member removed", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            res.exceptionOrNull()?.message ?: "Failed to remove member",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            { member: com.example.myapplication.data.community.model.Member ->
                // Show role selection dialog: Admin or Member
                val ctx = requireContext()
                val options = arrayOf("ADMIN", "MEMBER")
                try {
                    val display = member.username ?: member.email ?: "Unknown"
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                        .setTitle("Change role for $display")
                        .setItems(options) { _, which ->
                            val newRole = options[which]
                            lifecycleScope.launch {
                                val email = member.email ?: ""
                                val res =
                                    CommunityRepository.getInstance(ctx).changeMemberRole(communityId, email, newRole)
                                if (res.isSuccess) {
                                    Toast.makeText(ctx, "Role updated", Toast.LENGTH_SHORT).show()
                                    loadMembers(communityId, rv, progress)
                                } else {
                                    Toast.makeText(
                                        ctx,
                                        res.exceptionOrNull()?.message ?: "Failed to update role",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                        .show()
                } catch (_: Exception) {
                }
            }
        )
    }

    // Ensure adapter is initialized for given UI elements
    private fun ensureAdapter(communityId: String, rv: RecyclerView, progress: ProgressBar) {
        if (!::membersAdapter.isInitialized) {
            membersAdapter = createAdapter(communityId, rv, progress)
            rv.adapter = membersAdapter
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(R.id.rv_members)
        val progress = view.findViewById<ProgressBar>(R.id.progress)

        val communityId = arguments?.getString("communityId")
        if (communityId.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Missing communityId", Toast.LENGTH_SHORT).show()
            return
        }

        // Header population intentionally omitted: no user card in member screen

        // Setup RecyclerView
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))

        // Determine admin rights from getAllMembers remote response and setup adapter
        lifecycleScope.launch {
            val repo = CommunityRepository.getInstance(requireContext())
            val userData = UserDataManager.getInstance(requireContext())

            // Read current user's email first (suspending) so we can compute admin status efficiently
            currentEmail = try {
                userData.getEmail()
            } catch (_: Exception) {
                null
            }

            // Force a fresh network fetch for members when the user opens the Members screen
            val membersResult = repo.fetchMembers(communityId, force = true)

            membersResult.onSuccess { members ->
                // Compute admin: user is admin if they appear in members with an admin role
                isAdmin = computeIsAdminFromMembers(members, currentEmail)

                android.util.Log.d(
                    "MembersFragment",
                    "Computed admin status from members: isAdmin=$isAdmin currentEmail=$currentEmail"
                )

                // Create adapter (or recreate if admin flag changed) and populate list immediately
                ensureAdapter(communityId, rv, progress)
                // If admin flag changed, recreate the adapter to reflect new permissions
                val recomputedAdmin = computeIsAdminFromMembers(members, currentEmail)
                if (::membersAdapter.isInitialized && isAdmin != recomputedAdmin) {
                    isAdmin = recomputedAdmin
                    membersAdapter = createAdapter(communityId, rv, progress)
                    rv.adapter = membersAdapter
                }
                membersAdapter.submitList(members)

                // Setup swipe-to-delete only for admins; detach otherwise
                if (isAdmin) {
                    setupSwipeToDelete(rv, communityId)
                } else {
                    // detach any existing swipe helper
                    try { swipeHelper?.attachToRecyclerView(null); swipeHelper = null } catch (_: Exception) {}
                }
            }.onFailure { err ->
                // Fallback: try local community cache for a best-effort admin flag
                android.util.Log.w(
                    "MembersFragment",
                    "fetchMembers failed: ${err.message}, falling back to local role check"
                )
                val comm = repo.getCommunityById(communityId)
                val role = comm?.role?.uppercase()
                isAdmin = role in adminRoles

                // On failure fallback: detach swipe helper (non-admin fallback)
                try { swipeHelper?.attachToRecyclerView(null); swipeHelper = null } catch (_: Exception) {}

                ensureAdapter(communityId, rv, progress)
                // attempt to load members via existing loader (shows progress)
                loadMembers(communityId, rv, progress)
            }
        }
    }

    private fun setupSwipeToDelete(rv: RecyclerView, communityId: String) {
        val deleteIcon = ResourcesCompat.getDrawable(resources, android.R.drawable.ic_menu_delete, null)

        // Attach swipe-to-confirm-delete helper (no reveal button)
        val touchHelper = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                // Prevent swipe for the current user's own card
                val pos = viewHolder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return 0
                val maybeSelf = membersAdapter.currentList.getOrNull(pos)
                if (maybeSelf?.email != null && !currentEmail.isNullOrBlank() && maybeSelf.email.equals(currentEmail, true)) {
                    return 0
                }
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val position = vh.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return

                if (!isAdmin) {
                    Toast.makeText(requireContext(), "Only admins can remove members", Toast.LENGTH_SHORT).show()
                    rv.adapter?.notifyItemChanged(position)
                    return
                }
                // Prevent removing self even if admin
                val maybeSelf = membersAdapter.currentList.getOrNull(position)
                if (maybeSelf?.email != null && !currentEmail.isNullOrBlank() && maybeSelf.email.equals(currentEmail, true)) {
                    Toast.makeText(requireContext(), "You cannot remove yourself", Toast.LENGTH_SHORT).show()
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
                com.example.myapplication.ui.common.AppDialogHelper.showConfirmation(
                    requireContext(),
                    R.string.delete_confirm_title,
                    R.string.delete_confirm_message,
                    positiveRes = R.string.delete_confirm_yes,
                    negativeRes = android.R.string.cancel,
                    onPositive = {
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
                )
            }

            override fun onChildDraw(
                c: Canvas,
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                // Foreground container (present in item_member layout)
                val fg = vh.itemView.findViewById<View>(R.id.fg_container)
                val tx = dX.coerceAtMost(0f)
                fg?.translationX = tx

                // Draw red bg + delete icon on left swipe
                val itemView = vh.itemView
                if (dX < 0) {
                    val bg = RectF(
                        itemView.right + dX,
                        itemView.top.toFloat(),
                        itemView.right.toFloat(),
                        itemView.bottom.toFloat()
                    )
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
        // detach previous if any
        try { swipeHelper?.attachToRecyclerView(null) } catch (_: Exception) {}
        swipeHelper = ItemTouchHelper(touchHelper)
        swipeHelper?.attachToRecyclerView(rv)
    }

    private fun loadMembers(communityId: String, rv: RecyclerView, progress: ProgressBar) {
        progress.visibility = View.VISIBLE
        rv.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val repo = CommunityRepository.getInstance(requireContext())
            // refresh members from server (force fresh to reflect recent changes)
            val res = repo.fetchMembers(communityId, force = true)
            progress.visibility = View.GONE

            res.onSuccess { members ->
                android.util.Log.d("MembersFragment", "Members loaded successfully: ${members.size} members")
                rv.visibility = View.VISIBLE
                // Recompute admin status using cached email and recreate adapter only if needed
                val newAdmin = computeIsAdminFromMembers(members, currentEmail)
                if (newAdmin != isAdmin) {
                    isAdmin = newAdmin
                    membersAdapter = createAdapter(communityId, rv, progress)
                    rv.adapter = membersAdapter
                }

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
