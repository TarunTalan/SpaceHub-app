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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

open class BaseFragment(@LayoutRes contentLayoutId: Int) : Fragment(contentLayoutId) {

    // Tag used for the loader overlay view so we can find/remove it later
    private val LOADER_TAG = "__base_loader_overlay__"

    override fun onDestroyView() {
        super.onDestroyView()
        // ensure loader removed to avoid leaking views
        hideLoader()
    }

    protected fun hideKeyboard() {
        try {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val token = view?.windowToken ?: activity?.currentFocus?.windowToken
            token?.let { imm.hideSoftInputFromWindow(it, 0) }
        } catch (_: Exception) {
            // swallow - best-effort utility
        }
    }

    protected fun setupKeyboardDismiss(root: View) {
        root.setOnClickListener { hideKeyboard() }
    }

    protected fun showLoader() {
        val root = view ?: return
        try {
            // Avoid adding multiple overlays in either fragment root or activity content
            val activityContent = activity?.findViewById<ViewGroup>(android.R.id.content)
            if (root.findViewWithTag<View>(LOADER_TAG) != null || activityContent?.findViewWithTag<View>(LOADER_TAG) != null) return

            val overlay = FrameLayout(requireContext()).apply {
                tag = LOADER_TAG
                // semi-transparent dark background (use extension to convert hex to color int)
                setBackgroundColor("#80000000".toColorInt())
                // Make sure overlay consumes all touch events so underlying UI is disabled
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                // ensure it's focusable for accessibility
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                // place above other UI
                try { elevation = 120f; translationZ = 120f } catch (_: Exception) {}
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

            // Prefer attaching to the Activity content so it covers full screen (bottom nav, toolbar etc.)
            val parent: ViewGroup? = activityContent ?: (root as? ViewGroup)
            parent?.addView(
                overlay,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        } catch (_: Exception) {
        }
    }

    protected fun hideLoader() {
        val root = view ?: return
        try {
            // Look for overlay in activity content first, then fragment root
            val activityContent = activity?.findViewById<ViewGroup>(android.R.id.content)
            val existingInActivity = activityContent?.findViewWithTag<View>(LOADER_TAG)
            if (existingInActivity != null) {
                activityContent.removeView(existingInActivity)
                return
            }
            val existingInRoot = root.findViewWithTag<View>(LOADER_TAG)
            if (existingInRoot != null) {
                (root as? ViewGroup)?.removeView(existingInRoot)
            }
        } catch (_: Exception) {
            // ignore
        }
    }

    protected fun setLoaderVisible(visible: Boolean) {
        if (visible) showLoader() else hideLoader()
    }

    /**
     * Navigate using Navigation component after a short delay while showing the loader overlay.
     * Subclasses can call this to give time for destination fragment to prepare resources.
     */
    protected fun navigateWithDelay(actionId: Int, args: android.os.Bundle? = null, delayMs: Long = 300L, navOptions: androidx.navigation.NavOptions? = null) {
        // Use lifecycleScope so launch is tied to fragment's lifecycle
        lifecycleScope.launch {
            try {
                // Show loader so user sees a transition indicator
                showLoader()
                // Wait a small amount to allow background work to start
                delay(delayMs)
                if (navOptions != null) {
                    try { findNavController().navigate(actionId, args, navOptions) } catch (_: Exception) {}
                } else {
                    try { findNavController().navigate(actionId, args) } catch (_: Exception) {}
                }
            } catch (_: Exception) {
                // ignore navigation exceptions
            } finally {
                // remove loader - destination may show its own loader when ready
                hideLoader()
            }
        }
    }
}
