package com.example.myapplication.ui.common

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.util.TypedValue
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.annotation.LayoutRes
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment

/**
 * Base fragment that provides common utilities used across multiple fragments.
 * Inherit from this instead of Fragment(...) to reuse helpers like keyboard dismissal
 * and a reusable fullscreen loader overlay.
 */
open class BaseFragment(@LayoutRes contentLayoutId: Int) : Fragment(contentLayoutId) {

    // Tag used for the loader overlay view so we can find/remove it later
    private val LOADER_TAG = "__base_loader_overlay__"

    override fun onDestroyView() {
        super.onDestroyView()
        // ensure loader removed to avoid leaking views
        hideLoader()
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

    // ------------------ Fullscreen loader helpers ------------------

    /**
     * Show a fullscreen, clickable semi-transparent overlay with a centered ProgressBar.
     * Multiple calls are idempotent: only one overlay will exist.
     */
    protected fun showLoader() {
        val root = view ?: return
        try {
            // Avoid adding multiple overlays
            if (root.findViewWithTag<View>(LOADER_TAG) != null) return

            val overlay = FrameLayout(requireContext()).apply {
                tag = LOADER_TAG
                // semi-transparent dark background (use extension to convert hex to color int)
                setBackgroundColor("#80000000".toColorInt())
                isClickable = true
                isFocusable = true
            }

            // Create a themed, sized progress indicator
            val loaderSize = try { resources.getDimensionPixelSize(com.example.myapplication.R.dimen.loader_size) } catch (_: Exception) { null }

            // Resolve tint color: try theme's colorPrimary (AppCompat), otherwise fall back to a color resource
            val tintColor: Int = run {
                val tv = TypedValue()
                if (requireContext().theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)) {
                    if (tv.resourceId != 0) ContextCompat.getColor(requireContext(), tv.resourceId) else tv.data
                } else {
                    try { ContextCompat.getColor(requireContext(), com.example.myapplication.R.color.primary_blue) } catch (_: Exception) { 0 }
                }
            }

            val progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleLarge).apply {
                isIndeterminate = true
                try { indeterminateTintList = ColorStateList.valueOf(tintColor) } catch (_: Exception) { }
            }

            val params = FrameLayout.LayoutParams(
                loaderSize ?: FrameLayout.LayoutParams.WRAP_CONTENT,
                loaderSize ?: FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            overlay.addView(progressBar, params)

            (root as? ViewGroup)?.addView(
                overlay,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        } catch (_: Exception) {
            // best-effort: if we fail to create loader, silently ignore so we don't break flow
        }
    }

    /**
     * Remove loader overlay if present.
     */
    protected fun hideLoader() {
        val root = view ?: return
        try {
            val existing = root.findViewWithTag<View>(LOADER_TAG) ?: return
            (root as? ViewGroup)?.removeView(existing)
        } catch (_: Exception) {
            // ignore
        }
    }

    /**
     * Convenience: show or hide loader based on boolean.
     */
    protected fun setLoaderVisible(visible: Boolean) {
        if (visible) showLoader() else hideLoader()
    }
}
