package com.example.myapplication.ui.dashboard

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.activityViewModels
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.data.network.SharedPrefsTokenStore
import com.example.myapplication.data.session.SessionManager
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.common.ProfileImageLoader
import com.example.myapplication.ui.common.ProfileSharedViewModel
import com.google.android.material.navigation.NavigationView
import androidx.core.content.edit

class DashboardFragment : BaseFragment(R.layout.fragment_dashboard) {

    private val sharedVm: ProfileSharedViewModel by activityViewModels()

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


        // Update drawer header with chosen user profile
        val headerView = navView.getHeaderView(0)
        val navHeaderTitle = headerView.findViewById<TextView>(R.id.nav_header_title)
        val profileImgView = headerView.findViewById<ImageView>(R.id.nav_header_profile_iv)

        // Use UserDataManager for centralized, reactive data access
        val userDataManager = com.example.myapplication.data.user.UserDataManager.getInstance(requireContext())

        // Observe username changes
        userDataManager.username.observe(viewLifecycleOwner) { username ->
            try {
                val name = username?.takeIf { it.isNotBlank() } ?: getString(R.string.username_fallback)
                navHeaderTitle?.text = "Hello, $name"
                view.findViewById<TextView>(R.id.tv_username)?.text = name
            } catch (_: Exception) {}
        }

        // Load profile image with automatic updates
        ProfileImageLoader.loadProfileImage(
            imageView = profileImgView,
            context = requireContext(),
            lifecycleOwner = viewLifecycleOwner,
            circular = true
        )

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