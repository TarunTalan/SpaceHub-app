package com.example.myapplication

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
import com.example.myapplication.data.network.SharedPrefsTokenStore
import com.example.myapplication.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private var defaultToolbarColor: Int = 0
    private var defaultStatusBarColor: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            WindowCompat.setDecorFitsSystemWindows(window, true)
        } catch (_: Exception) {}
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        defaultToolbarColor = ContextCompat.getColor(this, R.color.dashboard_toolbar)
        defaultStatusBarColor = window.statusBarColor
        binding.toolbar.setBackgroundColor(defaultToolbarColor)
        try {
            binding.toolbar.backgroundTintList = ColorStateList.valueOf(defaultToolbarColor)
        } catch (_: Exception) {}
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
                R.id.dashboardFragment, R.id.searchFragment, R.id.chatRoomFragment, R.id.profileFragment
            )
            val isVisible = destination.id in bottomNavVisibleDestinations
            binding.bottomNavView.visibility = if (isVisible) View.VISIBLE else View.GONE
            binding.bottomNavIndicator.visibility = if (isVisible) View.VISIBLE else View.GONE
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

        // Setup animated indicator bar
        setupBottomNavIndicator()
        if (!token.isNullOrEmpty()) {
            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val lastScreen = prefs.getString("last_screen", null)
            when (lastScreen) {
                "dashboard" -> { navController.navigate(R.id.dashboardFragment); return }
                "choose_profile" -> { navController.navigate(R.id.chooseProfilePicFragment); return }
                "username" -> { navController.navigate(R.id.usernameFragment); return }
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
                R.id.dashboardFragment, R.id.searchFragment, R.id.chatRoomFragment, R.id.profileFragment
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
        } catch (_: Exception) {}
        window.statusBarColor = color
    }
    fun resetToolbarColor() {
        binding.toolbar.setBackgroundColor(defaultToolbarColor)
        try {
            binding.toolbar.backgroundTintList = ColorStateList.valueOf(defaultToolbarColor)
        } catch (_: Exception) {}
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
            R.id.chatRoomFragment -> 2
            R.id.profileFragment -> 3
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

}
