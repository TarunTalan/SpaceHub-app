package com.example.myapplication

import android.os.Bundle
import android.graphics.Rect
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.example.myapplication.databinding.ActivityMainBinding
import android.content.Context
import android.view.WindowManager
import androidx.core.view.WindowCompat

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Ensure decor fits system windows so adjustResize works reliably
        try { WindowCompat.setDecorFitsSystemWindows(window, true) } catch (_: Exception) {}
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ensure window resizes when IME appears. Set at runtime to override any edge-to-edge side effects.
        try {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        } catch (_: Exception) {}

        // Find the NavHostFragment
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment

        // Get the NavController
        navController = navHostFragment.navController

        // Determine whether to show onboarding. Show only on first cold start.
        try {
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val shouldShowOnboarding = prefs.getBoolean("show_onboarding", true)

            val navInflater = navController.navInflater
            val graph = navInflater.inflate(R.navigation.auth_nav_graph)

            if (shouldShowOnboarding) {
                graph.setStartDestination(R.id.onboardingFragment)
                prefs.edit().putBoolean("show_onboarding", false).apply()
            } else {
                graph.setStartDestination(R.id.loginFragment)
            }

            navController.graph = graph
        } catch (_: Exception) {
            // If anything fails, fall back to the XML-defined graph already linked to NavHostFragment
        }

        // Handle back press with OnBackPressedDispatcher (modern API)
        onBackPressedDispatcher.addCallback(this) {
            // Treat several auth screens as top-level destinations where back should prompt for exit.
            val topLevelDestinations = setOf(
                R.id.loginFragment,
                R.id.onboardingFragment,
                R.id.signupFragment,
                R.id.nameSignupFragment
            )

            val currentId = try { navController.currentDestination?.id } catch (_: Exception) { null }

            val shouldConfirmExit = try {
                // If current destination is one of the top-level auth destinations, confirm exit.
                currentId != null && currentId in topLevelDestinations
            } catch (_: Exception) {
                true
            }

            if (shouldConfirmExit) {
                showExitConfirmationDialog()
                return@addCallback
            }

            // Otherwise perform normal navigation
            try {
                if (!navController.navigateUp()) {
                    if (isEnabled) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            } catch (_: Exception) {
                if (isEnabled) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
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
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
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
        AlertDialog.Builder(this)
            .setTitle("Exit App")
            .setMessage("Are you sure you want to exit SpaceHub?")
            .setPositiveButton("Yes") { _, _ ->
                // Exit the app
                finish()
            }
            .setNegativeButton("No") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }
}
