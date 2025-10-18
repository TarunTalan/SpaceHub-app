package com.example.myapplication.ui.common

import android.content.Context
import android.content.pm.ActivityInfo
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment

/**
 * Base fragment that provides common utilities used across multiple fragments.
 * Inherit from this instead of Fragment(...) to reuse helpers like keyboard dismissal.
 *
 * This class also ensures fragments lock the hosting activity to portrait while
 * the fragment's view is active and restores the previous orientation afterward.
 */
open class BaseFragment(@LayoutRes contentLayoutId: Int) : Fragment(contentLayoutId) {

    // store previous orientation so we can restore it when fragment is destroyed
    private var previousOrientation: Int? = null

    override fun onResume() {
        super.onResume()
        try {
            val act = activity ?: return
            // Save previous orientation only once per fragment lifecycle
            if (previousOrientation == null) {
                previousOrientation = act.requestedOrientation
            }
            // Lock to portrait while this fragment is active
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } catch (_: Exception) {
            // best-effort; swallow exceptions to avoid breaking fragment lifecycle
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            val act = activity
            // Restore previous orientation if we changed it
            if (act != null && previousOrientation != null) {
                act.requestedOrientation = previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                previousOrientation = null
            }
        } catch (_: Exception) {
            // ignore
        }
    }

    /**
     * Hides the soft keyboard.
     */
    protected fun hideKeyboard() {
        try {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val token = view?.windowToken ?: activity?.currentFocus?.windowToken
            token?.let { imm.hideSoftInputFromWindow(it, 0) }
        } catch (_: Exception) {
            // swallow - best-effort utility
        }
    }
    /**
     * Sets a click listener on the provided root view to hide the keyboard when tapping outside inputs.
     */
    protected fun setupKeyboardDismiss(root: View) {
        root.setOnClickListener { hideKeyboard() }
    }
}
