package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import android.content.Context
import com.example.myapplication.data.network.SharedPrefsTokenStore
import com.example.myapplication.databinding.ActivityMainBinding
import androidx.lifecycle.ViewModelProvider
import com.example.myapplication.data.chat.websocket.DirectChatWebSocketService
import com.example.myapplication.data.chat.websocket.ChatWebSocketService
import com.example.myapplication.ui.common.ProfileSharedViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private var defaultToolbarColor: Int = 0
    private var defaultStatusBarColor: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            WindowCompat.setDecorFitsSystemWindows(window, true)
        } catch (_: Exception) {
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        defaultToolbarColor = ContextCompat.getColor(this, R.color.dashboard_toolbar)
        defaultStatusBarColor = window.statusBarColor
        binding.toolbar.setBackgroundColor(defaultToolbarColor)
        try {
            binding.toolbar.backgroundTintList = ColorStateList.valueOf(defaultToolbarColor)
        } catch (_: Exception) {
        }
        // Set transparent dark status bar
        window.statusBarColor = 0x80000000.toInt() // 50% black
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        navController = navHostFragment?.navController ?: return
        setSupportActionBar(binding.toolbar)
        val appBarConfiguration = androidx.navigation.ui.AppBarConfiguration(navController.graph)
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.toolbar.title = ""
        binding.toolbar.navigationIcon = null
        navController.addOnDestinationChangedListener { _, destination, _ ->
            supportActionBar?.setDisplayShowTitleEnabled(false)
            binding.toolbar.title = ""
            binding.toolbar.navigationIcon = null
            when (destination.id) {
                R.id.dashboardFragment -> setToolbarColorRes(R.color.dashboard_toolbar)
                else -> resetToolbarColor()
            }
            val bottomNavVisibleDestinations = setOf(
                R.id.dashboardFragment,
                R.id.searchFragment,
                R.id.createCommunityOrGroupFragment,
                R.id.searchFriendForChatFragment,
                R.id.profileFragment
            )
            val isVisible = destination.id in bottomNavVisibleDestinations
            binding.bottomNavView.visibility = if (isVisible) View.VISIBLE else View.GONE
            binding.bottomNavIndicator.visibility = if (isVisible) View.VISIBLE else View.GONE
            // Ensure the center add overlay is hidden when bottom nav is hidden
            try {
                binding.centerAddOverlay.visibility = if (isVisible) View.VISIBLE else View.GONE
            } catch (_: Exception) {
                // fallback: try findViewById
                try {
                    val overlay = findViewById<View>(R.id.center_add_overlay)
                    overlay?.visibility = if (isVisible) View.VISIBLE else View.GONE
                } catch (_: Exception) {}
            }
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val token = SharedPrefsTokenStore(this).getAccessToken()
        val navInflater = navController.navInflater
        val graph = navInflater.inflate(R.navigation.auth_nav_graph)
        if (!token.isNullOrEmpty()) {
            graph.setStartDestination(R.id.dashboardFragment)
        }
        navController.graph = graph
        val bottomNav = binding.bottomNavView
        NavigationUI.setupWithNavController(bottomNav, navController)

        // Wire the center overlay ImageView to select the center menu item
        try {
            val centerOverlay = findViewById<View>(R.id.center_add_overlay)
            android.util.Log.d("MainActivity", "center_add_overlay found=${centerOverlay != null}")
            android.util.Log.d("MainActivity", "center_add_overlay initialVisibility=${centerOverlay?.visibility}")
            val iv = findViewById<android.widget.ImageView>(R.id.iv_center_add)
            android.util.Log.d("MainActivity", "center overlay iv found=${iv != null}")
            android.util.Log.d("MainActivity", "center overlay iv drawable=${iv?.drawable}")
            centerOverlay?.setOnClickListener { try { bottomNav.selectedItemId = R.id.createCommunityOrGroupFragment } catch (_: Exception) {} }
            // Hide overlay when bottom nav is not visible
            navController.addOnDestinationChangedListener { _, destination, _ ->
                val bottomVisible = destination.id in setOf(
                    R.id.dashboardFragment, R.id.searchFragment, R.id.createCommunityOrGroupFragment, R.id.searchFriendForChatFragment, R.id.profileFragment
                )
                centerOverlay?.visibility = if (bottomVisible) View.VISIBLE else View.GONE
            }
            // Ensure the overlay is on top of the BottomNavigationView and not clipped
            bottomNav.post {
                try {
                    centerOverlay?.bringToFront()
                    centerOverlay?.translationZ = 64f
                    centerOverlay?.elevation = 64f
                    centerOverlay?.requestLayout()
                    iv?.requestLayout()
                    bottomNav.invalidate()
                } catch (_: Exception) {}
            }
            // Extra safety: ensure overlay is front after whole root is laid out
            binding.root.post {
                try {
                    centerOverlay?.bringToFront()
                    centerOverlay?.translationZ = 96f
                    centerOverlay?.elevation = 96f
                    centerOverlay?.visibility = View.VISIBLE
                    centerOverlay?.requestLayout()
                    bottomNav.invalidate()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        // Setup animated indicator bar
        setupBottomNavIndicator()
        // Register logout receiver so app can cleanup runtime state when SessionManager broadcasts logout
        try {
            val filter = IntentFilter("com.example.myapplication.ACTION_LOGOUT")
            registerReceiver(logoutReceiver, filter)
        } catch (_: Exception) {}
        if (!token.isNullOrEmpty()) {
            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val lastScreen = prefs.getString("last_screen", null)
            when (lastScreen) {
                "dashboard" -> {
                    navController.navigate(R.id.dashboardFragment); return
                }

                "choose_profile" -> {
                    navController.navigate(R.id.chooseProfilePicFragment); return
                }

                "username" -> {
                    navController.navigate(R.id.usernameFragment); return
                }
            }
            val username = prefs.getString("username", null)
            val uploadedProfileUrl = prefs.getString("uploaded_profile_url", null)
            when {
                username.isNullOrBlank() && !uploadedProfileUrl.isNullOrBlank() -> navController.navigate(R.id.usernameFragment)
                username.isNullOrBlank() && uploadedProfileUrl.isNullOrBlank() -> navController.navigate(R.id.chooseProfilePicFragment)
                !username.isNullOrBlank() && uploadedProfileUrl.isNullOrBlank() -> navController.navigate(R.id.chooseProfilePicFragment)
                else -> navController.navigate(R.id.dashboardFragment)
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            val currentId = navController.currentDestination?.id
            val goToOnboardingWhenBack = setOf(R.id.loginFragment, R.id.nameSignupFragment)
            if (currentId != null && currentId in goToOnboardingWhenBack) {
                val startDest = navController.graph.startDestinationId
                val popped = navController.popBackStack(startDest, false)
                if (!popped) {
                    navController.navigate(R.id.onboardingFragment) { launchSingleTop = true }
                }
                return@addCallback
            }
            val bottomNavDestinations = setOf(
                R.id.dashboardFragment,
                R.id.searchFragment,
                R.id.createCommunityOrGroupFragment,
                R.id.searchFriendForChatFragment,
                R.id.profileFragment
            )
            if (currentId != null && currentId in bottomNavDestinations) {
                if (currentId != R.id.dashboardFragment) {
                    navController.navigate(R.id.dashboardFragment)
                    return@addCallback
                } else {
                    finish()
                    return@addCallback
                }
            }
            val topLevelDestinations = setOf(
                R.id.onboardingFragment, R.id.chooseProfilePicFragment, R.id.usernameFragment, R.id.dashboardFragment
            )
            if (currentId != null && currentId in topLevelDestinations) {
                if (currentId == R.id.dashboardFragment) {
                    finish()
                    return@addCallback
                }
                showExitConfirmationDialog()
                return@addCallback
            }
            if (!navController.navigateUp()) {
                showExitConfirmationDialog()
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText) {
                val outRect = Rect()
                v.getGlobalVisibleRect(outRect)
                val rawX = ev.rawX.toInt()
                val rawY = ev.rawY.toInt()
                if (!outRect.contains(rawX, rawY)) {
                    v.clearFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(v.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showExitConfirmationDialog() {
        com.example.myapplication.ui.common.AppDialogHelper.showConfirmation(
            this,
            R.string.exit_app_title,
            R.string.exit_app_message,
            positiveRes = android.R.string.ok,
            negativeRes = android.R.string.cancel,
            onPositive = { finish() },
            themeRes = R.style.ThemeOverlay_MyApplication_MaterialAlertDialog
        )
    }

    fun setToolbarColorRes(colorResId: Int) {
        val color = ContextCompat.getColor(this, colorResId)
        binding.toolbar.setBackgroundColor(color)
        try {
            binding.toolbar.backgroundTintList = ColorStateList.valueOf(color)
        } catch (_: Exception) {
        }
        window.statusBarColor = color
    }

    fun resetToolbarColor() {
        binding.toolbar.setBackgroundColor(defaultToolbarColor)
        try {
            binding.toolbar.backgroundTintList = ColorStateList.valueOf(defaultToolbarColor)
        } catch (_: Exception) {
        }
        window.statusBarColor = defaultStatusBarColor
    }

    override fun onSupportNavigateUp(): Boolean {
        return try {
            navController.navigateUp() || super.onSupportNavigateUp()
        } catch (_: Exception) {
            super.onSupportNavigateUp()
        }
    }

    private fun setupBottomNavIndicator() {
        // Wait for layout to be ready
        binding.bottomNavView.post {
            val menuItemCount = binding.bottomNavView.menu.size()
            if (menuItemCount == 0) return@post

            // Calculate width of each menu item
            val bottomNavWidth = binding.bottomNavView.width
            val itemWidth = bottomNavWidth / menuItemCount

            // Set initial position based on selected item
            updateIndicatorPosition(binding.bottomNavView.selectedItemId, itemWidth, false)

            // Listen for item selections
            binding.bottomNavView.setOnItemSelectedListener { menuItem ->
                updateIndicatorPosition(menuItem.itemId, itemWidth, true)
                NavigationUI.onNavDestinationSelected(menuItem, navController)
                true
            }

            // Also update when navigation changes
            navController.addOnDestinationChangedListener { _, destination, _ ->
                updateIndicatorPosition(destination.id, itemWidth, true)
            }
        }
    }

    private fun updateIndicatorPosition(menuItemId: Int, itemWidth: Int, animate: Boolean) {
        val position = when (menuItemId) {
            R.id.dashboardFragment -> 0
            R.id.searchFragment -> 1
            R.id.createCommunityOrGroupFragment -> 2
            R.id.searchFriendForChatFragment -> 3
            R.id.profileFragment -> 4
            else -> 0
        }

        val targetX = (position * itemWidth) + (itemWidth / 2) - (binding.bottomNavIndicator.width / 2)

        if (animate) {
            binding.bottomNavIndicator.animate()
                .translationX(targetX.toFloat())
                .setDuration(300)
                .start()
        } else {
            binding.bottomNavIndicator.translationX = targetX.toFloat()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            try {
                val centerOverlay = findViewById<View>(R.id.center_add_overlay)
                centerOverlay?.bringToFront()
                centerOverlay?.translationZ = 120f
                centerOverlay?.elevation = 120f
                centerOverlay?.requestLayout()
                android.util.Log.d("MainActivity", "onWindowFocusChanged: centerOverlay visible=${centerOverlay?.visibility}")
            } catch (_: Exception) {}
        }
    }

    // Register a receiver to respond to logout events (clears runtime state)
    private val logoutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            try {
                // Disconnect websockets
                try { DirectChatWebSocketService.getInstance(applicationContext).disconnect() } catch (_: Exception) {}
                try { ChatWebSocketService.getInstance(applicationContext).disconnect() } catch (_: Exception) {}

                // Clear any in-memory shared viewmodels (profile image / selection)
                try {
                    val vm = ViewModelProvider(this@MainActivity).get(ProfileSharedViewModel::class.java)
                    vm.clear()
                } catch (_: Exception) {}

                // Close and delete databases to ensure clean state
                try { com.example.myapplication.data.community.database.CommunityDatabase.clearAndClose(applicationContext) } catch (_: Exception) {}
                try { com.example.myapplication.data.groups.database.GroupsDatabase.clearAndClose(applicationContext) } catch (_: Exception) {}

                // Optionally, clear navigation backstack and navigate to onboarding
                try {
                    val nav = navController
                    val navOptions = androidx.navigation.NavOptions.Builder().setPopUpTo(R.id.auth_nav_graph, true).build()
                    nav.navigate(R.id.action_dashboardFragment_to_onboardingFragment, null, navOptions)
                } catch (_: Exception) { }
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(logoutReceiver)
        } catch (_: Exception) {}
    }
}
