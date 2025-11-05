package com.example.myapplication.ui.dashboard

import android.animation.ObjectAnimator
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
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
import com.google.android.material.floatingactionbutton.FloatingActionButton

class DashboardFragment : BaseFragment(R.layout.fragment_dashboard) {

    private val sharedVm: ProfileSharedViewModel by activityViewModels()
    private val communityVm: CommunityViewModel by viewModels()

    // Store reference to badge update function
    private var updateBadgeFn: (() -> Unit)? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Show loader while initial UI setup runs (use BaseFragment helper)
        try { showLoader() } catch (_: Exception) { }


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
                    findNavController().navigate(R.id.action_dashboardFragment_to_createCommunityFragment)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }

        // Setup Your Communities Recycler
        val rvYourCommunities = view.findViewById<RecyclerView>(R.id.rv_your_communities)
        // Empty state container (illustration with texts)
        val emptyIllustrationContainer = view.findViewById<View>(R.id.illustration)
        val yourAdapter = YourCommunityAdapter { item ->
            try {
                val args = Bundle().apply { putString("communityId", item.communityId) }
                findNavController().navigate(R.id.action_dashboardFragment_to_communityDetailFragment, args)
            } catch (_: Exception) { }
        }
        rvYourCommunities?.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
        rvYourCommunities?.adapter = yourAdapter

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

        // Toggle expand/collapse for "Your Community"
        val ivToggle = view.findViewById<ImageView>(R.id.iv_toggle_your_comm)
        var yourCommExpanded = true
        fun applyToggleState(animated: Boolean = true) {
            val targetRotation = if (yourCommExpanded) 0f else 180f
            rvYourCommunities?.visibility = if (yourCommExpanded) View.VISIBLE else View.GONE
            if (ivToggle != null) {
                if (animated) {
                    ObjectAnimator.ofFloat(ivToggle, View.ROTATION, ivToggle.rotation, targetRotation).setDuration(200).start()
                } else {
                    ivToggle.rotation = targetRotation
                }
            }
        }
        ivToggle?.setOnClickListener {
            yourCommExpanded = !yourCommExpanded
            applyToggleState()
        }
        // initialize expanded
        applyToggleState(animated = false)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Show combined (owned + joined) "My communities" list
                communityVm.observeMyCommunities().collect { list ->
                    yourAdapter.submitList(list)
                    // Ensure any pending swipe spinner is stopped when data arrives
                    swipe?.isRefreshing = false
                    // Empty state toggle: show illustration when no communities
                    val isEmpty = list.isNullOrEmpty()
                    emptyIllustrationContainer?.visibility = if (isEmpty) View.VISIBLE else View.GONE
                    rvYourCommunities?.visibility = if (isEmpty) View.GONE else View.VISIBLE
                }
            }
        }

        // Initial setup complete — hide loader
        try { hideLoader() } catch (_: Exception) { }
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