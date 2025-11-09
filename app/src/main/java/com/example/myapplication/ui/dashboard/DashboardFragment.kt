package com.example.myapplication.ui.dashboard

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
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
import kotlinx.coroutines.async
import com.google.android.material.navigation.NavigationView
import androidx.core.content.edit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.ui.common.ProfileSharedViewModel
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.myapplication.data.community.repository.CommunityRepository
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.toColorInt
import com.example.myapplication.data.user.UserDataManager
import com.example.myapplication.ui.dashboard.adapter.CommunityListAdapter
import com.example.myapplication.ui.dashboard.adapter.CommunityUi
import com.example.myapplication.ui.group.LocalGroupsViewModel

class DashboardFragment : BaseFragment(R.layout.fragment_dashboard) {

    private val sharedVm: ProfileSharedViewModel by activityViewModels()
    private val communityVm: CommunityViewModel by viewModels()
    private val localGroupsVm: LocalGroupsViewModel by viewModels()

    // Store reference to badge update function
    private var updateBadgeFn: (() -> Unit)? = null
    private val roleBackfilled = mutableSetOf<String>()

    private lateinit var localGroupsAdapter: CommunityListAdapter
    private var rvLocalGroups: RecyclerView? = null
    private var tvLocalGroupsHeader: TextView? = null

    private val TAG = "DashboardLocalGroups"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        Log.d(TAG, "onViewCreated: DashboardFragment created")
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
                // Open unified notifications inbox (friend + group join requests)
                navigateWithDelay(R.id.action_dashboardFragment_to_notificationsFragment)
            }
        }

        // Load and display incoming friend requests count
        fun updateBadge() {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    // friend requests
                    val friendRepo = com.example.myapplication.data.friends.repository.FriendsRepository.getInstance(requireContext())
                    val friendRes = friendRepo.getIncomingRequests()
                    val friendCount = friendRes.getOrNull()?.size ?: 0

                    // local community join requests (pending join requests)
                    val commRepo = CommunityRepository.getInstance(requireContext())
                    val joinRes = commRepo.getMyPendingRequests()
                    val joinCount = joinRes.getOrNull()?.size ?: 0

                    val total = friendCount + joinCount
                    if (total > 0) {
                        tvBadge?.text = if (total > 99) "99+" else total.toString()
                        tvBadge?.visibility = View.VISIBLE
                    } else {
                        tvBadge?.visibility = View.GONE
                    }
                } catch (_: Exception) {
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
        val userDataManager = UserDataManager.getInstance(requireContext())

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
                R.id.nav_logout -> {
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


        // Setup Your Communities Recycler
        val rvYourCommunities = view.findViewById<RecyclerView>(R.id.rv_your_communities)
        val rvJoinedCommunities = view.findViewById<RecyclerView>(R.id.rv_joined_communities)
        val emptyIllustrationContainer = view.findViewById<View>(R.id.illustration)
        // Header labels and toggles for sections (declare early so helper can reference them)
        val tvMyCommunitiesHeader = view.findViewById<TextView>(R.id.tv_my_communities)
        val tvJoinedCommunitiesHeader = view.findViewById<TextView>(R.id.tv_joined_communities)
        val ivToggleYour = view.findViewById<ImageView>(R.id.iv_toggle_your_comm)
        val ivToggleJoined = view.findViewById<ImageView>(R.id.iv_toggle_joined_comm)
        // Track emptiness of each section to decide when to show the illustration
        var isMyCommunitiesEmpty = true
        var isJoinedCommunitiesEmpty = true
        var isLocalGroupsEmpty = true

        fun updateIllustrationVisibility() {
            try {
                emptyIllustrationContainer?.post {
                    try {
                        // Decide whether to show the global illustration only when ALL sections are empty
                        val showIllustration = isMyCommunitiesEmpty && isJoinedCommunitiesEmpty && isLocalGroupsEmpty

                        // Toggle per-section headers and lists depending on their own emptiness
                        try { tvMyCommunitiesHeader?.visibility = if (isMyCommunitiesEmpty) View.GONE else View.VISIBLE } catch (_: Exception) {}
                        try { ivToggleYour?.visibility = if (isMyCommunitiesEmpty) View.GONE else View.VISIBLE } catch (_: Exception) {}
                        try { rvYourCommunities?.visibility = if (isMyCommunitiesEmpty) View.GONE else View.VISIBLE } catch (_: Exception) {}

                        try { tvJoinedCommunitiesHeader?.visibility = if (isJoinedCommunitiesEmpty) View.GONE else View.VISIBLE } catch (_: Exception) {}
                        try { ivToggleJoined?.visibility = if (isJoinedCommunitiesEmpty) View.GONE else View.VISIBLE } catch (_: Exception) {}
                        try { rvJoinedCommunities?.visibility = if (isJoinedCommunitiesEmpty) View.GONE else View.VISIBLE } catch (_: Exception) {}

                        try { tvLocalGroupsHeader?.visibility = if (isLocalGroupsEmpty) View.GONE else View.VISIBLE } catch (_: Exception) {}
                        try { rvLocalGroups?.visibility = if (isLocalGroupsEmpty) View.GONE else View.VISIBLE } catch (_: Exception) {}

                        if (showIllustration) {
                            emptyIllustrationContainer?.visibility = View.VISIBLE
                            emptyIllustrationContainer?.bringToFront()
                            emptyIllustrationContainer?.requestLayout()
                        } else {
                            emptyIllustrationContainer?.visibility = View.GONE
                            try { (emptyIllustrationContainer?.parent as? View)?.invalidate() } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        // Ensure initial illustration visibility reflects current (initially-empty) adapters quickly
        updateIllustrationVisibility()
        val yourAdapter = YourCommunityAdapter { item ->
            runCatching { navigateWithDelay(R.id.action_dashboardFragment_to_communityDetailFragment, Bundle().apply { putString("communityId", item.communityId) }) }
        }
        val joinedAdapter = YourCommunityAdapter { item ->
            runCatching { navigateWithDelay(R.id.action_dashboardFragment_to_communityDetailFragment, Bundle().apply { putString("communityId", item.communityId) }) }
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
                val communityRepo = CommunityRepository.getInstance(requireContext())
                val friendsRepo = com.example.myapplication.data.friends.repository.FriendsRepository.getInstance(requireContext())
                val email = com.example.myapplication.data.user.UserDataManager.getInstance(requireContext()).getEmail()

                try {
                    // Fetch all data in parallel
                    val communitiesDeferred = async { communityRepo.fetchMyCommunitiesRemote(email) }
                    val pendingRequestsDeferred = async { communityRepo.getMyPendingRequests() }
                    val incomingFriendRequestsDeferred = async { friendsRepo.getIncomingRequests() }

                    // Also refresh local groups
                    localGroupsVm.loadGroups()

                    // Wait for all requests to complete
                    val communitiesRes = communitiesDeferred.await()
                    pendingRequestsDeferred.await() // Force execution
                    incomingFriendRequestsDeferred.await() // Force execution

                    // Update badge count after refreshing incoming friend requests
                    updateBadge()

                    communitiesRes.onFailure { e ->
                        isMyCommunitiesEmpty = true
                        isJoinedCommunitiesEmpty = true
                        // keep current isLocalGroupsEmpty value
                        updateIllustrationVisibility()
                        android.widget.Toast.makeText(requireContext(), e.message ?: "Failed to refresh communities", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    // On unexpected refresh error (e.g., SQLite busy), treat lists as empty and show illustration
                    isMyCommunitiesEmpty = true
                    isJoinedCommunitiesEmpty = true
                    isLocalGroupsEmpty = true
                    updateIllustrationVisibility()
                    android.widget.Toast.makeText(requireContext(), "Failed to refresh: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                } finally {
                    // Stop spinner regardless of outcome
                    swipe.isRefreshing = false
                }
            }
        }

        // Prepare swipe visuals (red bg + leave icon)
        val swipeBgPaint = Paint().apply { color = "#E53935".toColorInt() }
        val leaveIcon = ResourcesCompat.getDrawable(resources, R.drawable.leave, null)
        fun dpToPx(dp: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

        // Toggles for sections
        var yourCommExpanded = true
        var joinedCommExpanded = true
        fun applyYourToggleState(animated: Boolean = true) {
            val targetRotation = if (yourCommExpanded) 0f else 180f
            // Only show the recycler when expanded AND the section is not empty
            rvYourCommunities?.visibility = if (yourCommExpanded && !isMyCommunitiesEmpty) View.VISIBLE else View.GONE
            ivToggleYour?.let { if (animated) ObjectAnimator.ofFloat(it, View.ROTATION, it.rotation, targetRotation).setDuration(200).start() else it.rotation = targetRotation }
        }
        fun applyJoinedToggleState(animated: Boolean = true) {
            val targetRotation = if (joinedCommExpanded) 0f else 180f
            // Only show the recycler when expanded AND the section is not empty
            rvJoinedCommunities?.visibility = if (joinedCommExpanded && !isJoinedCommunitiesEmpty) View.VISIBLE else View.GONE
            ivToggleJoined?.let { if (animated) ObjectAnimator.ofFloat(it, View.ROTATION, it.rotation, targetRotation).setDuration(200).start() else it.rotation = targetRotation }
        }
        ivToggleYour?.setOnClickListener { yourCommExpanded = !yourCommExpanded; applyYourToggleState() }
        ivToggleJoined?.setOnClickListener { joinedCommExpanded = !joinedCommExpanded; applyJoinedToggleState() }
        applyYourToggleState(animated = false)
        applyJoinedToggleState(animated = false)

        // Collect and split lists into owned vs joined
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    communityVm.observeMyCommunities().collect { list ->
                        try {
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
                            // update per-section emptiness and decide illustration visibility
                            isMyCommunitiesEmpty = myCommunities.isEmpty()
                            isJoinedCommunitiesEmpty = joinedCommunities.isEmpty()
                            updateIllustrationVisibility()
                        } catch (e: Exception) {
                            // If processing the emitted list fails (DB busy, mapping error), treat community lists as empty so illustration shows
                            isMyCommunitiesEmpty = true
                            isJoinedCommunitiesEmpty = true
                            updateIllustrationVisibility()
                            Log.e(TAG, "Error processing community list", e)
                        }
                    }
                } catch (e: Exception) {
                    // If observing/collecting the flow fails (e.g., no such table), show illustration and log the error
                    isMyCommunitiesEmpty = true
                    isJoinedCommunitiesEmpty = true
                    updateIllustrationVisibility()
                    Log.e(TAG, "Failed to collect communities flow", e)
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
                            val membersRes = repo.fetchMembers(item.communityId, force = true)
                            val members = membersRes.getOrNull() ?: emptyList()
                            val myEmail = com.example.myapplication.data.user.UserDataManager.getInstance(ctx).getEmail()
                            val othersAdmin = members.any { m ->
                                !m.email.equals(myEmail, true) && (m.role.equals("ADMIN", true) || m.role.equals("OWNER", true))
                            }
                            if (!othersAdmin) {
                                com.example.myapplication.ui.common.AppDialogHelper.showConfirmation(ctx, "Can't leave as sole admin", "Change community admin before leaving.", positiveText = ctx.getString(android.R.string.ok), negativeText = "", onPositive = {
                                    adapter.notifyItemChanged(pos)
                                })
                                return@launch
                            }
                            // Show confirmation even if other admins exist
                            com.example.myapplication.ui.common.AppDialogHelper.showConfirmation(ctx, "Leave community", "You are an admin. Leave community?", positiveText = "Leave", negativeText = ctx.getString(android.R.string.cancel), onPositive = {
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
                            })
                        } else {
                            // Normal member: confirm leave
                            com.example.myapplication.ui.common.AppDialogHelper.showConfirmation(ctx, "Leave community", "Do you want to leave ${item.name}?", positiveText = "Leave", negativeText = ctx.getString(android.R.string.cancel), onPositive = {
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
                            })
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

        // Local Groups section
        rvLocalGroups = view.findViewById(R.id.rv_local_groups_dashboard)
        tvLocalGroupsHeader = view.findViewById(R.id.tv_local_groups)
        localGroupsAdapter = CommunityListAdapter({ item ->
            // On click, fetch group details before navigating
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    Log.d(TAG, "Clicked local group: id=${item.communityId}, name=${item.name}")
                    showLoader()
                    val repo = com.example.myapplication.data.groups.repository.LocalGroupRepository.getInstance(requireContext())
                    val res = repo.getLocalGroupDetails(item.communityId)
                    hideLoader()
                    if (res.isSuccess) {
                        val data = res.getOrNull()
                        Log.d(TAG, "getLocalGroupDetails success: $data")
                        navigateWithDelay(
                            R.id.localGroupDetailFragment,
                             Bundle().apply {
                                 putString("communityId", data?.id)
                                 putString("name", data?.name)
                                 putString("imageUrl", data?.imageUrl?.toString())
                                 putString("description", data?.description)
                                 putInt("totalMembers", data?.totalMembers ?: 0)
                                 putStringArrayList("memberEmails", ArrayList(data?.memberEmails ?: emptyList()))
                             }
                         )
                        } else {
                            Log.e(TAG, "getLocalGroupDetails failed: ${res.exceptionOrNull()?.message}", res.exceptionOrNull())
                            android.widget.Toast.makeText(requireContext(), res.exceptionOrNull()?.message ?: "Failed to load group details", android.widget.Toast.LENGTH_SHORT).show()
                        }
                } catch (e: Exception) {
                    hideLoader()
                    Log.e(TAG, "Exception in local group click handler", e)
                    android.widget.Toast.makeText(requireContext(), e.message ?: "Failed to load group details", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        })
        rvLocalGroups?.layoutManager = LinearLayoutManager(requireContext())
        rvLocalGroups?.adapter = localGroupsAdapter
        // Divider (optional)
        try {
            val deco = DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
            ContextCompat.getDrawable(requireContext(), R.drawable.divider_thin)?.let { d -> deco.setDrawable(d) }
            rvLocalGroups?.addItemDecoration(deco)
        } catch (_: Exception) {}
        // Observe local groups from ViewModel
        Log.d(TAG, "onViewCreated: Setting up local groups ViewModel observer")
        viewLifecycleOwner.lifecycleScope.launch {
            Log.d(TAG, "onViewCreated: Entered localGroupsVm.groups launch block")
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Log.d(TAG, "onViewCreated: Entered repeatOnLifecycle for localGroupsVm.groups")
                try {
                    localGroupsVm.groups.collect { list ->
                        try {
                            Log.d(TAG, "localGroupsVm.groups.collect: list size = ${list.size}")
                            val ui = list.map { g ->
                                val communityId = g.id
                                val idInt = try { communityId.toInt() } catch (_: Exception) { communityId.hashCode() }
                                val imageUrl = g.imageUrl as? String
                                val mapped = CommunityUi(
                                    communityId = communityId,
                                    id = idInt,
                                    name = g.name,
                                    imageUrl = imageUrl,
                                    subtitle = "${g.totalMembers} members",
                                    isLocal = true,
                                    isRequested = false,
                                    isOwner = false,
                                    isAdmin = false,
                                    isMember = true
                                )
                                Log.d(TAG, "Mapped local group: $mapped from DataX: $g")
                                mapped
                            }
                            Log.d(TAG, "Updating localGroupsAdapter with ${ui.size} items: $ui")
                            localGroupsAdapter.submitList(ui)
                            // Hide header if empty
                            tvLocalGroupsHeader?.visibility = if (ui.isEmpty()) View.GONE else View.VISIBLE
                            rvLocalGroups?.visibility = if (ui.isEmpty()) View.GONE else View.VISIBLE
                            // update local groups emptiness and maybe show/hide illustration
                            isLocalGroupsEmpty = ui.isEmpty()
                            updateIllustrationVisibility()
                        } catch (e: Exception) {
                            // On error reading local groups (DB busy etc.) assume empty so illustration can show
                            isLocalGroupsEmpty = true
                            updateIllustrationVisibility()
                            Log.e(TAG, "Error processing local groups", e)
                        }
                    }
                } catch (e: Exception) {
                    isLocalGroupsEmpty = true
                    updateIllustrationVisibility()
                    Log.e(TAG, "Failed to collect local groups flow", e)
                }
                // trigger load once
                Log.d(TAG, "onViewCreated: Calling localGroupsVm.loadGroups() on dashboard start")
                localGroupsVm.loadGroups()
            }
        }
        Log.d(TAG, "onViewCreated: Setting up savedStateHandle observer for local_group_created_item")
        // Observe savedStateHandle for local_group_created_item and prepend to local groups list
        try {
            val nav = findNavController()
            fun observeHandle(handleProvider: (() -> androidx.lifecycle.SavedStateHandle?)?) {
                try {
                    val handle = try { handleProvider?.invoke() } catch (_: Exception) { null }
                    handle ?: return
                    handle.getLiveData<Bundle>("local_group_created_item").observe(viewLifecycleOwner) { bundle ->
                        Log.d(TAG, "savedStateHandle observer fired: bundle = $bundle")
                        if (bundle != null) {
                            try {
                                val id = bundle.getString("id") ?: return@observe
                                val already = localGroupsAdapter.currentList.any { it.communityId == id }
                                Log.d(TAG, "Checking for duplicate: already = $already, id = $id")
                                if (already) {
                                    handle.remove<Bundle>("local_group_created_item")
                                    return@observe
                                }
                                val name = bundle.getString("name") ?: "Unnamed"
                                val imageUrl = bundle.getString("imageUrl") ?: bundle.getString("previewUri")
                                val totalMembers = bundle.getInt("totalMembers", 0)
                                val idInt = try { id.toInt() } catch (_: Exception) { id.hashCode() }
                                val newUi = CommunityUi(
                                    communityId = id,
                                    id = idInt,
                                    name = name,
                                    imageUrl = imageUrl,
                                    subtitle = "${totalMembers} members",
                                    isLocal = true,
                                    isRequested = false,
                                    isOwner = true,
                                    isAdmin = false,
                                    isMember = true
                                )
                                val current = localGroupsAdapter.currentList
                                val updated = listOf(newUi) + current
                                Log.d(TAG, "Prepending new local group to adapter: $newUi")
                                localGroupsAdapter.submitList(updated)
                                try { rvLocalGroups?.scrollToPosition(0) } catch (_: Exception) {}
                                handle.remove<Bundle>("local_group_created_item")
                                tvLocalGroupsHeader?.visibility = View.VISIBLE
                                rvLocalGroups?.visibility = View.VISIBLE
                            } catch (e: Exception) { Log.e(TAG, "Error adding new local group", e) }
                        }
                    }
                } catch (e: Exception) { Log.e(TAG, "Error in observeHandle", e) }
            }
            runCatching { observeHandle { nav.getBackStackEntry(R.id.dashboardFragment).savedStateHandle } }
            try { observeHandle { nav.currentBackStackEntry?.savedStateHandle } } catch (_: Exception) {}
            try { observeHandle { nav.previousBackStackEntry?.savedStateHandle } } catch (_: Exception) {}
        } catch (e: Exception) { Log.e(TAG, "Error setting up savedStateHandle observer", e) }

        // Observe refresh_local_groups flag to trigger API reload after group creation
        try {
            val nav = findNavController()
            nav.currentBackStackEntry?.savedStateHandle?.getLiveData<Boolean>("refresh_local_groups")?.observe(viewLifecycleOwner) { shouldRefresh ->
                if (shouldRefresh == true) {
                    Log.d(TAG, "refresh_local_groups flag detected, reloading local groups from API")
                    localGroupsVm.loadGroups()
                    nav.currentBackStackEntry?.savedStateHandle?.set("refresh_local_groups", false)
                }
            }
        } catch (_: Exception) {}
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
