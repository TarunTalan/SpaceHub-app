package com.example.myapplication.ui.common

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment

/**
 * Base fragment that provides common utilities used across multiple fragments.
 * Inherit from this instead of Fragment(...) to reuse helpers like keyboard dismissal.
 */
open class BaseFragment(@LayoutRes contentLayoutId: Int) : Fragment(contentLayoutId) {

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

