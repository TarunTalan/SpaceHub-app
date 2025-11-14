package com.example.myapplication.ui.dashboard

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.data.network.SharedPrefsTokenStore
import com.example.myapplication.data.session.SessionManager
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.community.adapter.YourCommunityAdapter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import com.google.android.material.navigation.NavigationView
import androidx.core.content.edit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.ui.common.ProfileSharedViewModel
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.myapplication.data.community.repository.CommunityRepository
import com.example.myapplication.data.user.UserDataManager
import com.example.myapplication.data.groups.repository.LocalGroupRepository
import com.example.myapplication.ui.dashboard.adapter.CommunityListAdapter
import com.example.myapplication.ui.dashboard.adapter.CommunityUi
import kotlinx.coroutines.flow.first
import com.example.myapplication.data.community.model.Community as CommunityModel

class DashboardFragment : BaseFragment(R.layout.fragment_dashboard) {

    private val sharedVm: ProfileSharedViewModel by activityViewModels()

    // Store reference to badge update function
    private var updateBadgeFn: (() -> Unit)? = null

    private var rvLocalGroups: RecyclerView? = null
    private var tvLocalGroupsHeader: TextView? = null

    private val TAG = "DashboardLocalGroups"

     // Debounce/animation helpers for the empty illustration to avoid flicker
     private var illustrationPendingRunnable: Runnable? = null
     private val illustrationDelayMillis: Long = 150
     private var isIllustrationVisible: Boolean = false
    // Track remote loading so we don't show the empty illustration while data is loading
    private var isLoadingData: Boolean = false

    companion object {
        // Startup refresh guard persisted across DashboardFragment instances so simple navigation
        // does not trigger a remote refresh repeatedly.
        private var initialDataLoaded: Boolean = false
        private var lastRefreshMillis: Long = 0L
        private const val refreshTTL: Long = 60_000L // 60s TTL before allowing another remote refresh
    }

     override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
         Log.d(TAG, "onViewCreated: DashboardFragment created")
         super.onViewCreated(view, savedInstanceState)

        // Use local non-null reference to avoid nullable property checks inside lambdas
        val rootView = view

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

        ivNotification?.setOnClickListener {
            runCatching {
                // Open unified notifications inbox (friend + group join requests)
                navigateWithDelay(R.id.action_dashboardFragment_to_notificationsFragment)
            }
        }

        // Store reference for onResume — will be assigned after we define the method below
        // (updateBadge is implemented as a private method on the fragment)
        updateBadgeFn = { updateBadge() }
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
            // username -> update nav header title and drawer username text
            userDataManager.usernameFlow.collect { uname ->
                try {
                    val name = if (!uname.isNullOrBlank()) uname else getString(R.string.username_fallback)
                    navHeaderTitle?.text = getString(R.string.hello_name, name)
                } catch (_: Exception) {}
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            userDataManager.profileImageUrlFlow.collect { url ->
                try {
                    com.example.myapplication.ui.common.ProfileImageHelper.loadProfileImageIntoView(requireContext(), profileImgView, url)
                } catch (_: Exception) {}
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

                                // Clear session and repository DBs. Do repository cleanup asynchronously but
                                // wait briefly to start DB transactions before navigating away so UI doesn't show stale data.
                                try {
                                    SessionManager.clearSession(requireContext())
                                    try { SharedPrefsTokenStore(requireContext()).clear() } catch (_: Exception) { }
                                    try { sharedVm.clear() } catch (_: Exception) { }

                                    // Perform cleanup and only navigate after cleanup completes (with a timeout)
                                    viewLifecycleOwner.lifecycleScope.launch {
                                        // Show a small loader for visibility
                                        runCatching { showLoader() }
                                        try {
                                            try {
                                                withTimeout(3000) {
                                                    // 1) Clear DataStore (user profile/preferences)
                                                    try {
                                                        // Use the already-created userDataManager instance to clear DataStore
                                                        userDataManager.clear()
                                                    } catch (_: Throwable) { Log.w(TAG, "Failed clearing DataStore") }

                                                    // 2) Clear community and related DB tables via repository helper
                                                    try {
                                                        val commRepo = CommunityRepository.getInstance(requireContext())
                                                        withContext(Dispatchers.IO) { commRepo.deleteAllCommunities() }
                                                    } catch (_: Throwable) { Log.w(TAG, "Failed deleting communities") }

                                                    // 3) Clear local groups
                                                    try {
                                                        val groupRepo = LocalGroupRepository.getInstance(requireContext())
                                                        withContext(Dispatchers.IO) { groupRepo.deleteAllGroups() }
                                                    } catch (_: Throwable) { Log.w(TAG, "Failed deleting local groups") }

                                                    // 4) Clear chat/room tables from community DB to remove residual chat/room data
                                                    try {
                                                        withContext(Dispatchers.IO) {
                                                            val db = com.example.myapplication.data.community.database.CommunityDatabase.getInstance(requireContext())
                                                            db.clearAllTables()
                                                        }
                                                    } catch (t: Throwable) { Log.w(TAG, "Failed clearing DB tables: ${t.message}") }
                                                }
                                            } catch (_: TimeoutCancellationException) {
                                                Log.w(TAG, "Logout cleanup timed out after 3s")
                                            } catch (_: Throwable) {
                                                Log.w(TAG, "Logout cleanup failed")
                                            }
                                        } finally {
                                            runCatching { hideLoader() }
                                            // Navigate only after cleanup attempt (success or timeout)
                                            val navOptions = NavOptions.Builder().setPopUpTo(R.id.auth_nav_graph, true).build()
                                            try { findNavController().navigate(R.id.action_dashboardFragment_to_onboardingFragment, null, navOptions) }
                                            catch (_: Exception) { try { findNavController().navigate(R.id.action_dashboardFragment_to_onboardingFragment) } catch (_: Exception) {} }
                                        }
                                    }
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

        // Toggle state for sections (persisted in SharedPreferences so state survives navigation/recreation)
        val prefs = requireContext().getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)
        var yourExpanded = prefs.getBoolean("your_expanded", true)
        var joinedExpanded = prefs.getBoolean("joined_expanded", true)

        // Helper to apply toggle state with 200ms animations (rotation + fade)
        fun applySectionToggle(iv: ImageView?, rv: RecyclerView?, expanded: Boolean) {
             try {
                 val duration = 200L
                if (expanded) {
                    try {
                        iv?.animate()?.rotation(0f)?.setDuration(duration)?.start()
                        rv?.visibility = View.VISIBLE
                        rv?.alpha = 0f
                        rv?.animate()?.alpha(1f)?.setDuration(duration)?.start()
                    } catch (_: Exception) {
                        // Fallback to immediate state change if animation or its listeners throw
                        try { iv?.rotation = 0f } catch (_: Exception) {}
                        try { rv?.visibility = View.VISIBLE; rv?.alpha = 1f } catch (_: Exception) {}
                    }
                } else {
                    try {
                        iv?.animate()?.rotation(180f)?.setDuration(duration)?.start()
                        rv?.animate()?.alpha(0f)?.setDuration(duration)?.withEndAction {
                            try { rv.visibility = View.GONE } catch (_: Exception) {}
                        }?.start()
                    } catch (_: Exception) {
                         // Fallback immediate hide
                         try { iv?.rotation = 180f } catch (_: Exception) {}
                         try { rv?.visibility = View.GONE; rv?.alpha = 0f } catch (_: Exception) {}
                     }
                 }
              } catch (_: Exception) {}
          }

        fun updateIllustrationVisibility() {
            try {
                // Evaluate desired state synchronously
                val showIllustration = isMyCommunitiesEmpty && isJoinedCommunitiesEmpty && isLocalGroupsEmpty

                // Immediately update headers/toggles/lists (no debounce) so the sections themselves won't flicker
                try { tvMyCommunitiesHeader?.visibility = if (isMyCommunitiesEmpty) View.GONE else View.VISIBLE } catch (_: Exception) {}
                try { ivToggleYour?.visibility = if (isMyCommunitiesEmpty) View.GONE else View.VISIBLE } catch (_: Exception) {}
                try { rvYourCommunities?.visibility = if (isMyCommunitiesEmpty) View.GONE else View.VISIBLE } catch (_: Exception) {}

                try { tvJoinedCommunitiesHeader?.visibility = if (isJoinedCommunitiesEmpty) View.GONE else View.VISIBLE } catch (_: Exception) {}
                try { ivToggleJoined?.visibility = if (isJoinedCommunitiesEmpty) View.GONE else View.VISIBLE } catch (_: Exception) {}
                try { rvJoinedCommunities?.visibility = if (isJoinedCommunitiesEmpty) View.GONE else View.VISIBLE } catch (_: Exception) {}

                try { tvLocalGroupsHeader?.visibility = if (isLocalGroupsEmpty) View.GONE else View.VISIBLE } catch (_: Exception) {}
                try { rvLocalGroups?.visibility = if (isLocalGroupsEmpty) View.GONE else View.VISIBLE } catch (_: Exception) {}

                // Debounce the actual illustration visibility change to avoid quick show/hide flicker
                // If the desired state equals current, cancel any pending runnable and do nothing.
                if (showIllustration == isIllustrationVisible) {
                    // nothing to do; cancel pending
                    illustrationPendingRunnable?.let { rootView.removeCallbacks(it) }
                    illustrationPendingRunnable = null
                    return
                }

                // Cancel previous pending runnable
                illustrationPendingRunnable?.let { rootView.removeCallbacks(it) }

                val runnable = Runnable {
                    try {
                        if (showIllustration) {
                            emptyIllustrationContainer?.apply {
                                alpha = 0f
                                visibility = View.VISIBLE
                                bringToFront()
                                try {
                                    animate().alpha(1f).setDuration(180).start()
                                } catch (_: Exception) {
                                    try { alpha = 1f } catch (_: Exception) {}
                                }
                            }
                        } else {
                            emptyIllustrationContainer?.apply {
                                try {
                                    animate().alpha(0f).setDuration(180).withEndAction {
                                        try { visibility = View.GONE } catch (_: Exception) {}
                                    }.start()
                                } catch (_: Exception) {
                                    try { alpha = 0f; visibility = View.GONE } catch (_: Exception) {}
                                }
                            }
                        }
                        isIllustrationVisible = showIllustration
                    } catch (_: Exception) {
                    }
                    illustrationPendingRunnable = null
                }

                illustrationPendingRunnable = runnable
                // Post with a small delay to avoid flicker when data toggles quickly
                rootView.postDelayed(runnable, illustrationDelayMillis)
            } catch (_: Exception) {}
        }

        // Ensure initial illustration visibility reflects current (initially-empty) adapters quickly
        updateIllustrationVisibility()
        // For "My communities" we don't want to display the admin/owner badge — pass false
        val yourAdapter = YourCommunityAdapter(showRoleBadge = false) { item ->
            runCatching { navigateWithDelay(R.id.action_dashboardFragment_to_communityDetailFragment, Bundle().apply { putString("communityId", item.communityId) }) }
        }
        // For Joined communities keep the role badge visible
        val joinedAdapter = YourCommunityAdapter { item ->
            runCatching { navigateWithDelay(R.id.action_dashboardFragment_to_communityDetailFragment, Bundle().apply { putString("communityId", item.communityId) }) }
        }
        rvYourCommunities?.layoutManager = LinearLayoutManager(requireContext())
        rvYourCommunities?.adapter = yourAdapter
        rvJoinedCommunities?.layoutManager = LinearLayoutManager(requireContext())
        rvJoinedCommunities?.adapter = joinedAdapter
        // Setup local groups RecyclerView and adapter (DB-backed list shown on fast-path)
        rvLocalGroups = view.findViewById(R.id.rv_local_groups_dashboard)
        // Find the local groups header text view (id used in layouts is tv_local_groups)
        tvLocalGroupsHeader = view.findViewById(R.id.tv_local_groups)
        val localGroupsAdapter = CommunityListAdapter({ item ->
            // Navigate to community detail (reuse community detail action). If you have a specific
            // local-group detail screen, replace this action id.
            // Use the dedicated local group detail destination so the app calls LocalGroup APIs
            runCatching {
                val args = Bundle().apply {
                    putString("communityId", item.communityId)
                    putString("name", item.name)
                    putString("imageUrl", item.imageUrl)
                }
                // Navigate directly to the fragment id to avoid mistakenly calling community APIs
                navigateWithDelay(R.id.localGroupDetailFragment, args)
            }
        })
        rvLocalGroups?.layoutManager = LinearLayoutManager(requireContext())
        rvLocalGroups?.adapter = localGroupsAdapter

        // Helper to apply a list of LocalGroup entities to the local groups adapter
        fun applyLocalGroupsToUi(list: List<com.example.myapplication.data.groups.model.LocalGroup>, currentEmail: String?) {
            try {
                val uiList = list.map { comm ->
                    val idInt = try { comm.groupId.toInt() } catch (_: Exception) { comm.groupId.hashCode() }
                    CommunityUi(
                        communityId = comm.groupId,
                        id = idInt,
                        name = comm.name,
                        imageUrl = comm.imageUrl,
                        subtitle = "${comm.memberCount} members",
                        isLocal = true,
                        isMember = currentEmail?.let { comm.memberEmails.contains(it) } ?: comm.memberEmails.contains("") ,
                        isOwner = comm.isOwner,
                        isAdmin = false
                    )
                }
                try { localGroupsAdapter.submitList(uiList) } catch (_: Exception) {}
                // update emptiness tracker used for illustration and header visibility
                isLocalGroupsEmpty = uiList.isEmpty()
                try {
                    // Show/hide local groups header based on emptiness
                    tvLocalGroupsHeader?.visibility = if (isLocalGroupsEmpty) View.GONE else View.VISIBLE
                    updateIllustrationVisibility()
                } catch (_: Exception) {}
            } catch (_: Exception) {}
        }

        // Ensure toggle icons are clickable and attach click listeners
        ivToggleYour?.isClickable = true
        ivToggleJoined?.isClickable = true
        ivToggleYour?.setOnClickListener {
             yourExpanded = !yourExpanded
             // persist
             prefs.edit { putBoolean("your_expanded", yourExpanded) }
             applySectionToggle(ivToggleYour, rvYourCommunities, yourExpanded)
         }
         ivToggleJoined?.setOnClickListener {
             joinedExpanded = !joinedExpanded
             // persist
             prefs.edit { putBoolean("joined_expanded", joinedExpanded) }
             applySectionToggle(ivToggleJoined, rvJoinedCommunities, joinedExpanded)
         }

        // Also make the header text clickable so users can tap the header row to toggle
        tvMyCommunitiesHeader?.isClickable = true
        tvMyCommunitiesHeader?.setOnClickListener {
            yourExpanded = !yourExpanded
            prefs.edit { putBoolean("your_expanded", yourExpanded) }
            applySectionToggle(ivToggleYour, rvYourCommunities, yourExpanded)
        }
        tvJoinedCommunitiesHeader?.isClickable = true
        tvJoinedCommunitiesHeader?.setOnClickListener {
            joinedExpanded = !joinedExpanded
            prefs.edit { putBoolean("joined_expanded", joinedExpanded) }
            applySectionToggle(ivToggleJoined, rvJoinedCommunities, joinedExpanded)
        }

        // Initialize visibility based on emptiness flags (expanded by default when non-empty)
        try { if (isMyCommunitiesEmpty) { rvYourCommunities?.visibility = View.GONE; ivToggleYour?.visibility = View.GONE } else { applySectionToggle(ivToggleYour, rvYourCommunities, yourExpanded) } } catch (_: Exception) {}
        try { if (isJoinedCommunitiesEmpty) { rvJoinedCommunities?.visibility = View.GONE; ivToggleJoined?.visibility = View.GONE } else { applySectionToggle(ivToggleJoined, rvJoinedCommunities, joinedExpanded) } } catch (_: Exception) {}

        // Pull-to-refresh: authoritative refresh from server -> update UI -> update DB. Use DB only as fallback.
        val swipe = view.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh_my_communities)

        // Helper: apply a list of communities (from DB snapshot) to the UI (same logic used in collector)
        fun applyCommunitiesToUi(list: List<CommunityModel>, email: String?) {
             try {
                 val adminRoleKeywords = listOf("ADMIN", "OWNER", "CREATOR", "MANAGER", "MODERATOR")

                // Deterministic map approach: normalize and prefer admin/owner entries
                val normalized = mutableMapOf<String, CommunityModel>()
                for (c in list) {
                    val idRaw = c.communityId.trim().takeIf { it.isNotBlank() }
                    val key = idRaw?.lowercase() ?: run {
                        val namePart = c.name.trim().lowercase()
                        val creatorPart = c.creatorId?.trim()?.lowercase() ?: ""
                        "${namePart}|${creatorPart}"
                    }
                     val existing = normalized[key]
                     if (existing == null) normalized[key] = c
                     else {
                        fun score(itm: CommunityModel): Int {
                            var s = 0
                            val role = itm.role?.trim()?.uppercase()
                            if (role != null && adminRoleKeywords.any { k -> role.contains(k) }) s += 4
                            if (itm.isOwner) s += 3
                            if (itm.isModerator) s += 2
                            if (itm.isMember) s += 1
                            return s
                        }
                        // choose the entry with the higher score (prefer admin/owner entries)
                        val keep = if (score(c) > score(existing)) c else existing
                        normalized[key] = keep
                     }
                }

                // Convert back to sorted list for RecyclerView (owner/moderator first, sorted by name)
                val sorted = normalized.values.sortedWith(compareByDescending<CommunityModel> { it.isOwner || it.isModerator }.thenBy { it.name })

                // Classify owners/admins (use role hints + isOwner/isModerator/creatorId match)
                val owners = sorted.filter { c ->
                     val roleRaw = c.role?.trim()?.uppercase()
                     val roleIndicatesAdmin = roleRaw != null && adminRoleKeywords.any { k -> roleRaw.contains(k) }
                     val creatorIsMe = !c.creatorId.isNullOrBlank() && c.creatorId.equals(email, true)
                     roleIndicatesAdmin || c.isOwner || c.isModerator || creatorIsMe
                 }

                val joined = sorted.filter { c -> c.isMember && !owners.any { o -> o.communityId == c.communityId } }

                // Update UI: adapters expect Community model
                try {
                    yourAdapter.submitList(owners)
                    joinedAdapter.submitList(joined)
                } catch (_: Exception) {}

                // Update emptiness trackers
                isMyCommunitiesEmpty = owners.isEmpty()
                isJoinedCommunitiesEmpty = joined.isEmpty()
                // Sync toggle visibility and section expanded state when data changes
                try {
                    if (isMyCommunitiesEmpty) {
                        rvYourCommunities?.visibility = View.GONE
                        ivToggleYour?.visibility = View.GONE
                    } else {
                        ivToggleYour?.visibility = View.VISIBLE
                        // Apply current expanded/collapsed state to the view
                        applySectionToggle(ivToggleYour, rvYourCommunities, yourExpanded)
                    }
                } catch (_: Exception) {}
                try {
                    if (isJoinedCommunitiesEmpty) {
                        rvJoinedCommunities?.visibility = View.GONE
                        ivToggleJoined?.visibility = View.GONE
                    } else {
                        ivToggleJoined?.visibility = View.VISIBLE
                        applySectionToggle(ivToggleJoined, rvJoinedCommunities, joinedExpanded)
                    }
                } catch (_: Exception) {}
                 // Ensure illustration and headers reflect new state
                 try { updateIllustrationVisibility() } catch (_: Exception) {}
             } catch (_: Exception) {
             }
         }

        // Initial load: authoritative API -> DB -> UI refresh
        fun loadAndApplyInitialData() {
             viewLifecycleOwner.lifecycleScope.launch {
                try { isLoadingData = true; updateIllustrationVisibility() } catch (_: Exception) {}
                 val repo = CommunityRepository.getInstance(requireContext())
                 val email = try { UserDataManager.getInstance(requireContext()).getEmail() } catch (_: Exception) { null }
                 showLoader()
                 try {
                    // Call remote to update DB, then read DB snapshot and apply to UI
                    val remoteRes = try { withContext(Dispatchers.IO) { repo.fetchMyCommunitiesRemote(email) } } catch (t: Throwable) { Result.failure<Unit>(t) }
                    // fetchMyCommunitiesRemote completed
                    // Read both the 'my' communities flow and all communities snapshot for diagnostics
                    val dbList = try { withContext(Dispatchers.IO) { repo.observeMyCommunities().first() } } catch (_: Throwable) { emptyList<CommunityModel>() }
                    val allList = try { withContext(Dispatchers.IO) { repo.observeAllCommunities().first() } } catch (_: Throwable) { emptyList<CommunityModel>() }
                    // DB snapshot sizes: my=${dbList.size}, all=${allList.size}
                    // If 'my' communities flow is empty but there are entries in all communities,
                    // it's likely the server inserted rows without relationship flags; use allList as fallback.
                    val sourceList = if (dbList.isEmpty() && allList.isNotEmpty()) {
                        Log.w(TAG, "myCommunities empty; using allCommunities as fallback to populate UI")
                        allList
                    } else dbList
                    applyCommunitiesToUi(sourceList, email)
                    if (remoteRes.isFailure) android.widget.Toast.makeText(requireContext(), "Failed to refresh communities", android.widget.Toast.LENGTH_SHORT).show()
                    // Use debounced animated update for illustration (avoid direct set to prevent flicker)
                    try { updateIllustrationVisibility() } catch (_: Exception) {}
                 } catch (t: Throwable) {
                     Log.e(TAG, "loadAndApplyInitialData failed", t)
                     // Fallback to DB snapshot
                     try {
                        val repo2 = CommunityRepository.getInstance(requireContext())
                        val dbList = withContext(Dispatchers.IO) { repo2.observeMyCommunities().first() }
                        val allList = withContext(Dispatchers.IO) { repo2.observeAllCommunities().first() }
                        // fallback DB snapshot sizes
                        val src = if (dbList.isEmpty() && allList.isNotEmpty()) allList else dbList
                        applyCommunitiesToUi(src, UserDataManager.getInstance(requireContext()).getEmail())
                        try { updateIllustrationVisibility() } catch (_: Exception) {}
                      } catch (e: Throwable) { Log.e(TAG, "fallback DB read failed", e) }
                 } finally {
                     try { hideLoader() } catch (_: Exception) {}
                     try { isLoadingData = false; updateIllustrationVisibility() } catch (_: Exception) {}
                 }
             }

        }

        // Load initial data on view created
        // DB-only loader for local groups (fast, used on navigation/ restart).
        fun loadLocalGroupsFromDb() {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val db = com.example.myapplication.data.groups.database.GroupsDatabase.getInstance(requireContext())
                    val dao = db.groupDao()
                    val entities = try { withContext(Dispatchers.IO) { dao.getAllGroupsFlow().first() } } catch (_: Throwable) { emptyList<com.example.myapplication.data.groups.model.LocalGroup>() }
                    val currentEmail = try { UserDataManager.getInstance(requireContext()).getEmail() } catch (_: Exception) { null }
                    applyLocalGroupsToUi(entities, currentEmail)
                } catch (_: Throwable) { }
            }
        }

        // Fast-path loader: read DB only on navigation, and only perform remote authoritative refresh
        // when `initialDataLoaded` is false or TTL expired.
        fun maybeLoadInitialData() {
            // Keep illustration hidden until this function's launched coroutines finish.
            val now = System.currentTimeMillis()
            val shouldRemoteRefresh = !initialDataLoaded || (now - lastRefreshMillis > refreshTTL)
            if (shouldRemoteRefresh) {
                // Authoritative remote refresh (will update DB) — run once per session or after TTL
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        try { isLoadingData = true; updateIllustrationVisibility() } catch (_: Exception) {}
                        loadAndApplyInitialData()
                    } catch (_: Exception) {
                    } finally {
                        initialDataLoaded = true
                        lastRefreshMillis = System.currentTimeMillis()
                        try { isLoadingData = false; updateIllustrationVisibility() } catch (_: Exception) {}
                    }
                }
            } else {
                // Fast-path: load communities and local groups from DB only (no network)
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        try { isLoadingData = true; updateIllustrationVisibility() } catch (_: Exception) {}
                        val repo = CommunityRepository.getInstance(requireContext())
                        val email = try { UserDataManager.getInstance(requireContext()).getEmail() } catch (_: Exception) { null }
                        val dbList = try { withContext(Dispatchers.IO) { repo.observeMyCommunities().first() } } catch (_: Throwable) { emptyList<CommunityModel>() }
                        val allList = try { withContext(Dispatchers.IO) { repo.observeAllCommunities().first() } } catch (_: Throwable) { emptyList<CommunityModel>() }
                        val sourceList = if (dbList.isEmpty() && allList.isNotEmpty()) allList else dbList
                        applyCommunitiesToUi(sourceList, email)
                        // Load local groups from DB (fast)
                        try { loadLocalGroupsFromDb() } catch (_: Exception) {}
                    } catch (_: Exception) {
                        // fallback to remote if DB read fails
                        try { loadAndApplyInitialData(); initialDataLoaded = true; lastRefreshMillis = System.currentTimeMillis() } catch (_: Exception) {}
                    } finally {
                        try { isLoadingData = false; updateIllustrationVisibility() } catch (_: Exception) {}
                    }
                }
            }
        }
        if (savedInstanceState == null) {
            try { isLoadingData = true; updateIllustrationVisibility() } catch (_: Exception) {}
            if (!initialDataLoaded) {
                maybeLoadInitialData()
            } else {
                // Fast DB-only load (no network). This ensures UI populates quickly without refreshing remote data.
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        try { isLoadingData = true; updateIllustrationVisibility() } catch (_: Exception) {}
                        val repo = CommunityRepository.getInstance(requireContext())
                        val email = try { UserDataManager.getInstance(requireContext()).getEmail() } catch (_: Exception) { null }
                        val dbList = try { withContext(Dispatchers.IO) { repo.observeMyCommunities().first() } } catch (_: Throwable) { emptyList<CommunityModel>() }
                        val allList = try { withContext(Dispatchers.IO) { repo.observeAllCommunities().first() } } catch (_: Throwable) { emptyList<CommunityModel>() }
                        val sourceList = if (dbList.isEmpty() && allList.isNotEmpty()) allList else dbList
                        applyCommunitiesToUi(sourceList, email)
                        try { loadLocalGroupsFromDb() } catch (_: Exception) {}
                    } catch (_: Exception) {
                        // If DB read fails, we still don't want to force a remote refresh here — skip.
                    } finally {
                        try { isLoadingData = false; updateIllustrationVisibility() } catch (_: Exception) {}
                    }
                }
            }
        } else {
            // Navigated back via bottom navigation or fragment recreation — do not refresh communities/groups.
            Log.d(TAG, "Skipping community/group refresh on fragment recreate (savedInstanceState != null)")
        }


        // Initial load (remote): fetch local groups from API and apply (used by swipe refresh or authoritative refresh)
        fun loadAndApplyLocalGroups() {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    try { isLoadingData = true; updateIllustrationVisibility() } catch (_: Exception) {}
                    val repo = LocalGroupRepository.getInstance(requireContext())
                    val res = try { withContext(Dispatchers.IO) { repo.getAllLocalGroups() } } catch (t: Throwable) { Result.failure<List<com.example.myapplication.data.groups.model.DataX>>(t) }
                    val groups = res.getOrNull() ?: emptyList()
                    // local groups fetched: size=${groups.size}
                    val currentEmail = try { UserDataManager.getInstance(requireContext()).getEmail() } catch (_: Exception) { null }
                    applyLocalGroupsToUi(groups.map { d ->
                        // Map DataX to LocalGroup model used above
                        com.example.myapplication.data.groups.model.LocalGroup(
                            groupId = d.id,
                            name = d.name,
                            description = d.description,
                            imageUrl = d.imageUrl as? String,
                            memberEmails = d.memberEmails,
                            memberCount = d.totalMembers,
                            createdByEmail = d.createdByEmail,
                            chatRoomCode = d.chatRoomCode,
                            createdAt = d.createdAt,
                            updatedAt = d.updatedAt
                        )
                    }, currentEmail)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed loading local groups: ${t.message}")
                } finally {
                    try { isLoadingData = false; updateIllustrationVisibility() } catch (_: Exception) {}
                }
            }
        }

        // Observe navigation savedState for local_group_created flag to refresh list
        try {
            val nav = findNavController()
            val entry = nav.getBackStackEntry(R.id.dashboardFragment)
            // Observe requests/invite flow: when a join request is accepted elsewhere, refresh communities
            entry.savedStateHandle.getLiveData<Boolean>("refresh_communities").observe(viewLifecycleOwner) { shouldRefresh ->
                if (shouldRefresh == true) {
                    try {
                        // Trigger the authoritative reload logic (fast-path aware)
                        maybeLoadInitialData()
                    } catch (_: Exception) {}
                    // Clear the flag to avoid repeated reloads
                    entry.savedStateHandle["refresh_communities"] = false
                }
            }
        } catch (_: Exception) {}

        // Load initial data for local groups on view created: use DB fast-path (avoid network on navigation)
        loadLocalGroupsFromDb()

        // Attach swipe refresh listener after functions are declared so local helpers are in-scope.
        swipe.setOnRefreshListener {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    // updateBadge will fetch friend and join request counts and update UI badge
                    Log.d(TAG, "Swipe refresh: refreshing only notifications")
                    updateBadge()
                    // Provide a visible confirmation so it's obvious the swipe only refreshed notifications
                    try { com.google.android.material.snackbar.Snackbar.make(rootView, "Notifications refreshed", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show() } catch (_: Exception) {}
                } catch (_: Exception) {}
                try { swipe.isRefreshing = false } catch (_: Exception) {}
            }
        }
        // (local groups refresh handled above)
    }

    // Override onResume to update badge count when returning to this screen
    override fun onResume() {
        super.onResume()
        try {
            updateBadgeFn?.invoke()
        } catch (_: Exception) {}
    }

    // Custom navigation function with optional delay
    private fun navigateWithDelay(actionId: Int, args: Bundle? = null, delayMillis: Long = 0) {
        try {
            viewLifecycleOwner.lifecycleScope.launch {
                // Optional delay before navigation
                if (delayMillis > 0) {
                    try {
                        kotlinx.coroutines.delay(delayMillis)
                    } catch (_: Exception) {}
                }

                // Perform the navigation
                findNavController().navigate(actionId, args)
            }
        } catch (_: Exception) {}
    }

    // NOTE: Fragment does not override onBackPressed; BaseFragment may handle back presses.

    // Private helper to update notification badge (friend requests + pending join requests)
    private fun updateBadge() {
        viewLifecycleOwner.lifecycleScope.launch {
            val tvBadge = view?.findViewById<TextView>(R.id.tv_notification_badge)
            try {
                Log.d(TAG, "updateBadge: starting")
                // friend requests
                val friendRepo = com.example.myapplication.data.friends.repository.FriendsRepository.getInstance(requireContext())
                val friendRes = friendRepo.getIncomingRequests()
                val friendCount = friendRes.getOrNull()?.size ?: 0

                // local community join requests (pending join requests)
                val commRepo = CommunityRepository.getInstance(requireContext())
                val joinRes = commRepo.getMyPendingRequests()
                val joinCount = joinRes.getOrNull()?.size ?: 0

                val total = friendCount + joinCount
                Log.d(TAG, "updateBadge: friend=$friendCount join=$joinCount total=$total")
                if (total > 0) {
                    tvBadge?.text = if (total > 99) "99+" else total.toString()
                    tvBadge?.visibility = View.VISIBLE
                } else {
                    tvBadge?.visibility = View.GONE
                }
            } catch (_: Exception) {
                try { tvBadge?.visibility = View.GONE } catch (_: Exception) {}
            }
        }
    }
}
