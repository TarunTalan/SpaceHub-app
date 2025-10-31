package com.example.myapplication

import android.content.res.ColorStateList
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.text.style.ForegroundColorSpan
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.get
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import com.example.myapplication.data.network.SharedPrefsTokenStore
import com.example.myapplication.databinding.ActivityMainBinding
import androidx.core.view.size

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
            binding.bottomNavView.visibility =
                if (destination.id in bottomNavVisibleDestinations) View.VISIBLE else View.GONE
            updateBottomNavUnderline(destination.id)
        }
        @Suppress("DEPRECATION") window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val token = SharedPrefsTokenStore(this).getAccessToken()
        val navInflater = navController.navInflater
        val graph = navInflater.inflate(R.navigation.auth_nav_graph)
        if (!token.isNullOrEmpty()) {
            graph.setStartDestination(R.id.dashboardFragment)
        }
        navController.graph = graph
        val bottomNav = binding.bottomNavView
        NavigationUI.setupWithNavController(bottomNav, navController)
        updateBottomNavUnderline(navController.currentDestination?.id ?: R.id.dashboardFragment)
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
    private fun updateBottomNavUnderline(menuItemId: Int) {
        val bottomNav = binding.bottomNavView
        if (bottomNav.visibility != View.VISIBLE) return
        bottomNav.post {
            val colorState = bottomNav.itemTextColor ?: bottomNav.itemIconTintList
            val selColor = colorState?.getColorForState(intArrayOf(android.R.attr.state_checked), ContextCompat.getColor(this, R.color.dashboard_toolbar))
                ?: ContextCompat.getColor(this, R.color.dashboard_toolbar)
            for (i in 0 until bottomNav.menu.size) {
                val item = bottomNav.menu[i]
                val rawTitle = item.title?.toString() ?: ""
                val spannable = SpannableString(rawTitle)
                if (item.itemId == menuItemId) {
                    spannable.setSpan(UnderlineSpan(), 0, spannable.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    spannable.setSpan(ForegroundColorSpan(selColor), 0, spannable.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                }
                item.title = spannable
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return try {
            navController.navigateUp() || super.onSupportNavigateUp()
        } catch (_: Exception) {
            super.onSupportNavigateUp()
        }
    }

}
