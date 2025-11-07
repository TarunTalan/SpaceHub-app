package com.example.myapplication.ui.dashboard

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.data.network.SharedPrefsTokenStore
import com.example.myapplication.data.session.SessionManager
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.community.adapter.YourCommunityAdapter
import com.example.myapplication.ui.community.viewmodel.CommunityViewModel
import kotlinx.coroutines.launch
import com.google.android.material.navigation.NavigationView
import androidx.core.content.edit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.ui.common.ProfileSharedViewModel
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.myapplication.data.community.repository.CommunityRepository
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.core.content.ContextCompat
import com.example.myapplication.data.dashboard.DashboardRepository
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.DrawableCompat

class DashboardFragment : BaseFragment(R.layout.fragment_dashboard) {

    private val sharedVm: ProfileSharedViewModel by activityViewModels()
    private val communityVm: CommunityViewModel by viewModels()

    // Store reference to badge update function
    private var updateBadgeFn: (() -> Unit)? = null
    private val roleBackfilled = mutableSetOf<String>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // No global loader here; we'll show a loader only during the one-time bootstrap below

        val drawerLayout = view.findViewById<DrawerLayout>(R.id.drawer_layout)
        val navView = view.findViewById<NavigationView>(R.id.navigation_view)

        val drawerIcon = view.findViewById<ImageView>(R.id.drawer_nav_icon)
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.START)

        drawerIcon.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        // Navigate to requests inbox on notification icon click
        val ivNotification = view.findViewById<ImageView>(R.id.iv_notification)
        val tvBadge = view.findViewById<TextView>(R.id.tv_notification_badge)

        ivNotification?.setOnClickListener {
            runCatching {
                findNavController().navigate(R.id.action_dashboardFragment_to_requestsInboxFragment)
            }
        }

        // Load and display pending requests count
        fun updateBadge() {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val repo = com.example.myapplication.data.community.repository.CommunityRepository.getInstance(requireContext())
                    val result = repo.getPendingRequestsCount()
                    result.onSuccess { count ->
                        if (count > 0) {
                            tvBadge?.text = if (count > 99) "99+" else count.toString()
                            tvBadge?.visibility = View.VISIBLE
                        } else {
                            tvBadge?.visibility = View.GONE
                        }
                    }.onFailure {
                        tvBadge?.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    tvBadge?.visibility = View.GONE
                }
            }
        }

        // Store reference for onResume
        updateBadgeFn = ::updateBadge
        // Update badge on fragment start
        updateBadge()

        // Update drawer header with chosen user profile
        val headerView = navView.getHeaderView(0)
        val navHeaderTitle = headerView.findViewById<TextView>(R.id.nav_header_title)
        val profileImgView = headerView.findViewById<ImageView>(R.id.nav_header_profile_iv)

        // Use UserDataManager for centralized, reactive data access
        val userDataManager = com.example.myapplication.data.user.UserDataManager.getInstance(requireContext())

        // Observe username and profile image via UserDataManager flows so nav header updates immediately.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // username -> update nav header title and drawer username text
                launch {
                    userDataManager.usernameFlow.collect { uname ->
                        try {
                            val name = if (!uname.isNullOrBlank()) uname else getString(R.string.username_fallback)
                            navHeaderTitle?.text = getString(R.string.hello_name, name)
                            view.findViewById<TextView>(R.id.tv_username)?.text = name
                        } catch (_: Exception) {}
                    }
                }

                // profile image -> load directly using helper
                launch {
                    userDataManager.profileImageUrlFlow.collect { url ->
                        try {
                            // Use same helper as other screens; it handles urls/uris/files/defaults
                            com.example.myapplication.ui.common.ProfileImageHelper.loadProfileImageIntoView(requireContext(), profileImgView, url)
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        // Handle navigation view item clicks
        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_settings -> {}
                R.id.nav_logout -> {
                    // Ask for confirmation before logging out
                    try {
                        com.example.myapplication.ui.common.AppDialogHelper.showConfirmation(
                            requireContext(),
                            R.string.logout_confirm_title,
                            R.string.logout_confirm_message,
                            positiveRes = R.string.logout_confirm_yes,
                            negativeRes = android.R.string.cancel,
                            onPositive = {
                                try {
                                    val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                    prefs.edit { putString("last_screen", "logout") }
                                } catch (_: Exception) { }

                                try {
                                    SessionManager.clearSession(requireContext())
                                    try { SharedPrefsTokenStore(requireContext()).clear() } catch (_: Exception) { }
                                    try { sharedVm.clear() } catch (_: Exception) { }

                                    val navOptions = NavOptions.Builder().setPopUpTo(R.id.auth_nav_graph, true).build()
                                    findNavController().navigate(R.id.action_dashboardFragment_to_onboardingFragment, null, navOptions)

                                } catch (_: Exception) {
                                    try { findNavController().navigate(R.id.action_dashboardFragment_to_onboardingFragment) } catch (_: Exception) { }
                                }
                            }
                        )
                    } catch (_: Exception) { }
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        // Persist that dashboard is the last visible screen so app restarts here
        try {
            val prefs = requireActivity().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            prefs.edit { putString("last_screen", "dashboard") }
        } catch (_: Exception) {
        }

        // Wire the add community button to navigate to CreateCommunityFragment
        try {
            val addComm = view.findViewById<ImageView>(R.id.add_comm)
            addComm?.setOnClickListener {
                try {
                    findNavController().navigate(R.id.action_dashboardFragment_to_createCommunityOrGroupFragment)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }

        // Setup Your Communities Recycler
        val rvYourCommunities = view.findViewById<RecyclerView>(R.id.rv_your_communities)
        val rvJoinedCommunities = view.findViewById<RecyclerView>(R.id.rv_joined_communities)
        val emptyIllustrationContainer = view.findViewById<View>(R.id.illustration)
        // Header labels for sections
        val tvMyCommunitiesHeader = view.findViewById<TextView>(R.id.tv_my_communities)
        val tvJoinedCommunitiesHeader = view.findViewById<TextView>(R.id.tv_joined_communities)
        val yourAdapter = YourCommunityAdapter { item ->
            runCatching { findNavController().navigate(R.id.action_dashboardFragment_to_communityDetailFragment, Bundle().apply { putString("communityId", item.communityId) }) }
        }
        val joinedAdapter = YourCommunityAdapter { item ->
            runCatching { findNavController().navigate(R.id.action_dashboardFragment_to_communityDetailFragment, Bundle().apply { putString("communityId", item.communityId) }) }
        }
        rvYourCommunities?.layoutManager = LinearLayoutManager(requireContext())
        rvYourCommunities?.adapter = yourAdapter
        rvJoinedCommunities?.layoutManager = LinearLayoutManager(requireContext())
        rvJoinedCommunities?.adapter = joinedAdapter
        // Dividers
        listOf(rvYourCommunities, rvJoinedCommunities).forEach { rv ->
            rv?.let {
                val deco = DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
                ContextCompat.getDrawable(requireContext(), R.drawable.divider_thin)?.let { d -> deco.setDrawable(d) }
                it.addItemDecoration(deco)
            }
        }

        // Pull-to-refresh: reload My Communities from server
        val swipe = view.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh_my_communities)
        swipe?.setOnRefreshListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val repo = CommunityRepository.getInstance(requireContext())
                val email = com.example.myapplication.data.user.UserDataManager.getInstance(requireContext()).getEmail()
                val res = repo.fetchMyCommunitiesRemote(email)
                // stop spinner regardless of outcome
                swipe.isRefreshing = false
                res.onFailure { e ->
                    try { android.widget.Toast.makeText(requireContext(), e.message ?: "Failed to refresh", android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                }
            }
        }

        // Prepare swipe visuals (red bg + leave icon)
        val swipeBgPaint = Paint().apply { color = Color.parseColor("#E53935") }
        val leaveIcon = ResourcesCompat.getDrawable(resources, R.drawable.leave, null)
        fun dpToPx(dp: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

        // Toggles for sections
        val ivToggleYour = view.findViewById<ImageView>(R.id.iv_toggle_your_comm)
        val ivToggleJoined = view.findViewById<ImageView>(R.id.iv_toggle_joined_comm)
        var yourCommExpanded = true
        var joinedCommExpanded = true
        fun applyYourToggleState(animated: Boolean = true) {
            val targetRotation = if (yourCommExpanded) 0f else 180f
            rvYourCommunities?.visibility = if (yourCommExpanded) View.VISIBLE else View.GONE
            ivToggleYour?.let { if (animated) ObjectAnimator.ofFloat(it, View.ROTATION, it.rotation, targetRotation).setDuration(200).start() else it.rotation = targetRotation }
        }
        fun applyJoinedToggleState(animated: Boolean = true) {
            val targetRotation = if (joinedCommExpanded) 0f else 180f
            rvJoinedCommunities?.visibility = if (joinedCommExpanded) View.VISIBLE else View.GONE
            ivToggleJoined?.let { if (animated) ObjectAnimator.ofFloat(it, View.ROTATION, it.rotation, targetRotation).setDuration(200).start() else it.rotation = targetRotation }
        }
        ivToggleYour?.setOnClickListener { yourCommExpanded = !yourCommExpanded; applyYourToggleState() }
        ivToggleJoined?.setOnClickListener { joinedCommExpanded = !joinedCommExpanded; applyJoinedToggleState() }
        applyYourToggleState(animated = false)
        applyJoinedToggleState(animated = false)

        // Collect and split lists into owned vs joined
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                communityVm.observeMyCommunities().collect { list ->
                    val email = com.example.myapplication.data.user.UserDataManager.getInstance(requireContext()).getEmail()
                    val myCommunities = list.filter {
                        it.isOwner || it.isModerator || (!it.creatorId.isNullOrBlank() && it.creatorId.equals(email, true))
                    }
                    val joinedCommunities = list.filter {
                        it.isMember && !it.isOwner && !it.isModerator && (it.creatorId.isNullOrBlank() || !it.creatorId.equals(email, true))
                    }
                    yourAdapter.submitList(myCommunities)
                    joinedAdapter.submitList(joinedCommunities)
                    swipe?.isRefreshing = false
                    val allEmpty = myCommunities.isEmpty() && joinedCommunities.isEmpty()
                    emptyIllustrationContainer?.visibility = if (allEmpty) View.VISIBLE else View.GONE

                    // Hide headers and arrows when respective lists are empty
                    val hasMy = myCommunities.isNotEmpty()
                    val hasJoined = joinedCommunities.isNotEmpty()
                    tvMyCommunitiesHeader?.visibility = if (hasMy) View.VISIBLE else View.GONE
                    ivToggleYour?.visibility = if (hasMy) View.VISIBLE else View.GONE
                    tvJoinedCommunitiesHeader?.visibility = if (hasJoined) View.VISIBLE else View.GONE
                    ivToggleJoined?.visibility = if (hasJoined) View.VISIBLE else View.GONE

                    rvYourCommunities?.visibility = if (myCommunities.isNotEmpty() && yourCommExpanded) View.VISIBLE else View.GONE
                    rvJoinedCommunities?.visibility = if (joinedCommunities.isNotEmpty() && joinedCommExpanded) View.VISIBLE else View.GONE

                    // Background role backfill: if some communities appear as joined but we might be admin,
                    // fetch members for a few of them once to update relationship flags in Room.
                    val repo = com.example.myapplication.data.community.repository.CommunityRepository.getInstance(requireContext())
                    val unknowns = joinedCommunities
                        .asSequence()
                        .filter { !roleBackfilled.contains(it.communityId) }
                        .take(3)
                        .toList()
                    if (unknowns.isNotEmpty()) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            unknowns.forEach { item ->
                                roleBackfilled.add(item.communityId)
                                runCatching { repo.fetchMembers(item.communityId) }
                            }
                        }
                    }
                }
            }
        }

        // Bootstrap communities and rooms once per email after auth completes
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            val userData = com.example.myapplication.data.user.UserDataManager.getInstance(ctx)
            val email = runCatching { userData.getEmail() }.getOrNull()
            if (!email.isNullOrBlank()) {
                val prefs = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val bootstrappedFor = prefs.getString("dashboard_bootstrap_email", null)
                if (bootstrappedFor != email) {
                    // Show a loader only for the first-time bootstrap
                    runCatching { showLoader() }
                    try {
                        // 1) Profile (to populate drawer/profile screens)
                        val dashRepo = com.example.myapplication.data.dashboard.DashboardRepository(ctx)
                        runCatching { dashRepo.getProfile() }
                        // 2) My communities + rooms
                        val repo = com.example.myapplication.data.community.repository.CommunityRepository.getInstance(ctx)
                        repo.bootstrapCommunitiesAndRooms()
                        // Mark as done for this email
                        prefs.edit { putString("dashboard_bootstrap_email", email) }
                    } finally {
                        runCatching { hideLoader() }
                    }
                }
            }
        }

        // Right-swipe to leave a community
        fun attachSwipeToLeave(rv: RecyclerView?, adapter: YourCommunityAdapter) {
            if (rv == null) return
            val touchHelper = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
                override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false
                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    val pos = viewHolder.bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return
                    val item = adapter.currentList.getOrNull(pos) ?: return

                    // If owner/admin: require other admin to exist (simple check via repo members count/roles)
                    val ctx = requireContext()
                    viewLifecycleOwner.lifecycleScope.launch {
                        val repo = CommunityRepository.getInstance(ctx)
                        // Determine if current user is owner/admin locally
                        val isOwnerOrAdmin = item.isOwner || item.isModerator || (!item.creatorId.isNullOrBlank() && item.creatorId.equals(com.example.myapplication.data.user.UserDataManager.getInstance(ctx).getEmail(), true))
                        if (isOwnerOrAdmin) {
                            // Fetch members and see if there exists another admin/owner
                            val membersRes = repo.fetchMembers(item.communityId)
                            val members = membersRes.getOrNull() ?: emptyList()
                            val myEmail = com.example.myapplication.data.user.UserDataManager.getInstance(ctx).getEmail()
                            val othersAdmin = members.any { m ->
                                !m.email.equals(myEmail, true) && (m.role.equals("ADMIN", true) || m.role.equals("OWNER", true))
                            }
                            if (!othersAdmin) {
                                AlertDialog.Builder(ctx)
                                    .setTitle("Can't leave as sole admin")
                                    .setMessage("Change community admin before leaving.")
                                    .setPositiveButton(android.R.string.ok) { _, _ ->
                                        // reset swipe
                                        adapter.notifyItemChanged(pos)
                                    }
                                    .setOnCancelListener { adapter.notifyItemChanged(pos) }
                                    .show()
                                return@launch
                            }
                            // Show confirmation even if other admins exist
                            AlertDialog.Builder(ctx)
                                .setTitle("Leave community")
                                .setMessage("You are an admin. Leave community?")
                                .setPositiveButton("Leave") { _, _ ->
                                    viewLifecycleOwner.lifecycleScope.launch {
                                        val res = repo.leaveCommunity(item.communityId, item.name)
                                        if (res.isSuccess) {
                                            // Refresh my communities
                                            runCatching {
                                                val email = com.example.myapplication.data.user.UserDataManager.getInstance(ctx).getEmail()
            									repo.fetchMyCommunitiesRemote(email)
                                            }
                                        } else {
                                            android.widget.Toast.makeText(ctx, "Failed to leave", android.widget.Toast.LENGTH_SHORT).show()
                                            adapter.notifyItemChanged(pos)
                                        }
                                    }
                                }
                                .setNegativeButton(android.R.string.cancel) { _, _ -> adapter.notifyItemChanged(pos) }
                                .setOnCancelListener { adapter.notifyItemChanged(pos) }
                                .show()
                        } else {
                            // Normal member: confirm leave
                            AlertDialog.Builder(ctx)
                                .setTitle("Leave community")
                                .setMessage("Do you want to leave ${item.name}?")
                                .setPositiveButton("Leave") { _, _ ->
                                    viewLifecycleOwner.lifecycleScope.launch {
                                        val res = repo.leaveCommunity(item.communityId, item.name)
                                        if (res.isSuccess) {
                                            runCatching {
                                                val email = com.example.myapplication.data.user.UserDataManager.getInstance(ctx).getEmail()
                                                repo.fetchMyCommunitiesRemote(email)
                                            }
                                        } else {
                                            android.widget.Toast.makeText(ctx, "Failed to leave", android.widget.Toast.LENGTH_SHORT).show()
                                            adapter.notifyItemChanged(pos)
                                        }
                                    }
                                }
                                .setNegativeButton(android.R.string.cancel) { _, _ -> adapter.notifyItemChanged(pos) }
                                .setOnCancelListener { adapter.notifyItemChanged(pos) }
                                .show()
                        }
                    }
                }
                override fun onChildDraw(c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
                    val itemView = viewHolder.itemView
                    if (dX > 0) {
                        val bg = RectF(itemView.left.toFloat(), itemView.top.toFloat(), itemView.left + dX, itemView.bottom.toFloat())
                        c.drawRect(bg, swipeBgPaint)
                        leaveIcon?.let { icon ->
                            val iconSize = dpToPx(24)
                            val iconMargin = dpToPx(16)
                            val top = itemView.top + (itemView.height - iconSize) / 2
                            val left = itemView.left + iconMargin
                            icon.setBounds(left, top, left + iconSize, top + iconSize)
                            DrawableCompat.setTint(icon, Color.WHITE)
                            icon.draw(c)
                        }
                    }
                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                }
            }
            ItemTouchHelper(touchHelper).attachToRecyclerView(rv)
        }
        // Attach to both lists (you may prefer only on joined communities list)
        attachSwipeToLeave(rvYourCommunities, yourAdapter)
        attachSwipeToLeave(rvJoinedCommunities, joinedAdapter)
    }

    override fun onResume() {
        super.onResume()
        try {
            (activity as? com.example.myapplication.MainActivity)?.setToolbarColorRes(R.color.dashboard_toolbar)
        } catch (_: Exception) {
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            (activity as? com.example.myapplication.MainActivity)?.resetToolbarColor()
        } catch (_: Exception) {
        }
    }
}
