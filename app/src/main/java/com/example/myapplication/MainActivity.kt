package com.example.myapplication

import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.example.myapplication.data.network.SharedPrefsTokenStore
import com.example.myapplication.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Ensure decor fits system windows so adjustResize works reliably
        try {
            WindowCompat.setDecorFitsSystemWindows(window, true)
        } catch (_: Exception) {
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ensure window resizes when IME appears. Set at runtime to override any edge-to-edge side effects.
        @Suppress("DEPRECATION")
        try {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        } catch (_: Exception) {
        }

        // Find the NavHostFragment
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment

        // Get the NavController
        navController = navHostFragment.navController

        // Determine whether to show onboarding based on authentication token.
        try {
            // Read token using the app's token store so we use the same storage keys.
            val token = SharedPrefsTokenStore(this).getAccessToken()

            val navInflater = navController.navInflater
            val graph = navInflater.inflate(R.navigation.auth_nav_graph)

            // Always keep the graph with onboarding as the start destination so it remains available
            navController.graph = graph

            if (token.isNullOrEmpty()) {
                // No token -> user not authenticated -> show onboarding (start destination)
                // Do nothing: onboarding is the start destination and will be shown.
            } else {
                // User already has a token; show logout screen on open (authenticated state)
                try {
                    navController.navigate(R.id.logoutFragment)
                } catch (_: Exception) {
                    // If navigation fails for any reason, fall back silently — the graph is still set.
                }
            }
        } catch (_: Exception) {
            // If anything fails, fall back to the XML-defined graph already linked to NavHostFragment
        }

        // Handle back press with OnBackPressedDispatcher (modern API)
        onBackPressedDispatcher.addCallback(this) {
            val currentId = try {
                navController.currentDestination?.id
            } catch (_: Exception) {
                null
            }

            // If the user is on login, nameSignup, or logout, go to onboarding and clear other fragments
            val goToOnboardingWhenBack = setOf(
                R.id.loginFragment,
                R.id.nameSignupFragment,
            )

            if (currentId != null && currentId in goToOnboardingWhenBack) {
                try {
                    val startDest = navController.graph.startDestinationId
                    // Prefer popBackStack to remove all fragments above the start destination.
                    val popped = try {
                        navController.popBackStack(startDest, false)
                    } catch (_: Exception) {
                        false
                    }
                    if (!popped) {
                        // If onboarding wasn't in the back stack, navigate to it (singleTop to avoid duplicates).
                        navController.navigate(R.id.onboardingFragment) {
                            launchSingleTop = true
                        }
                    }
                } catch (_: Exception) {
                    // If navigation fails, fallback to default behavior
                    try {
                        if (!navController.navigateUp()) showExitConfirmationDialog()
                    } catch (_: Exception) {
                        showExitConfirmationDialog()
                    }
                }
                return@addCallback
            }

            // Consider these as top-level destinations where back should prompt for exit.
            val topLevelDestinations = setOf(
                R.id.onboardingFragment, R.id.logoutFragment
            )

            if (currentId != null && currentId in topLevelDestinations) {
                // On a top-level screen -> confirm exit
                showExitConfirmationDialog()
                return@addCallback
            }

            // Otherwise try normal navigation (go to previous fragment). If that's not possible, confirm exit.
            try {
                if (!navController.navigateUp()) {
                    showExitConfirmationDialog()
                }
            } catch (_: Exception) {
                showExitConfirmationDialog()
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // When user taps outside an EditText, clear focus and hide keyboard
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

    /**
     * Shows a confirmation dialog before exiting the app.
     */
    private fun showExitConfirmationDialog() {
        AlertDialog.Builder(this).setTitle("Exit App").setMessage("Are you sure you want to exit SpaceHub?")
            .setPositiveButton("Yes") { _, _ ->
                // Exit the app
                finish()
            }.setNegativeButton("No") { dialog, _ ->
                dialog.dismiss()
            }.setCancelable(true).show()
    }
}
