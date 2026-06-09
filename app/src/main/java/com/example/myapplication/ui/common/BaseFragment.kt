package com.example.myapplication.ui.common

import android.content.Context
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.util.TypedValue
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // If the layout contains a NestedScrollView with id `scroll_auth`, enable automatic scrolling
        try { setupImeAutoScroll(view) } catch (_: Exception) { }
        // Disable scrolling by default (will be enabled when keyboard appears)
        try { setupKeyboardScrollControl(view) } catch (_: Exception) { }
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
        val gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Only dismiss keyboard on confirmed single tap (not part of double-tap)
                // Check if the tap is outside any EditText to avoid dismissing when tapping on input fields
                val touchedView = findViewAt(root, e.rawX, e.rawY)
                if (touchedView !is EditText) {
                    hideKeyboard()
                }
                return true
            }

            override fun onDown(e: MotionEvent): Boolean {
                // Return true to indicate we want to handle gestures
                return true
            }
        })

        root.setOnTouchListener { v, event ->
            // Let the gesture detector handle the event
            gestureDetector.onTouchEvent(event)
            // Don't consume the event so children can still receive touches
            false
        }
    }

    private fun findViewAt(root: View, x: Float, y: Float): View? {
        if (root !is ViewGroup) {
            val location = IntArray(2)
            root.getLocationOnScreen(location)
            val viewX = location[0]
            val viewY = location[1]
            if (x >= viewX && x < viewX + root.width && y >= viewY && y < viewY + root.height) {
                return root
            }
            return null
        }

        // Check children in reverse order (top-most views first)
        for (i in root.childCount - 1 downTo 0) {
            val child = root.getChildAt(i)
            if (child.visibility == View.VISIBLE) {
                val foundView = findViewAt(child, x, y)
                if (foundView != null) return foundView
            }
        }

        // If no child was found, check the ViewGroup itself
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        val viewX = location[0]
        val viewY = location[1]
        if (x >= viewX && x < viewX + root.width && y >= viewY && y < viewY + root.height) {
            return root
        }

        return null
    }

    // Automatically scroll `NestedScrollView` (id: scroll_auth) when any EditText inside gains focus.
    private fun setupImeAutoScroll(root: View) {
        val scroll = root.findViewById<NestedScrollView?>(com.example.myapplication.R.id.scroll_auth) ?: return

        // Recursively attach focus listeners to EditText instances
        fun attachToChildren(view: View) {
            if (view is EditText) {
                view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        // compute Y position of the view relative to the scroll's direct child
                        var relativeY = 0
                        var p: View? = v
                        while (p != null && p !== scroll && p.parent is View) {
                            relativeY += p.top
                            val parent = p.parent
                            p = if (parent is View) parent else null
                        }
                        // scroll with a small offset so the field isn't flush to the top
                        val offset = (resources.displayMetrics.density * 20).toInt()
                        scroll.post { scroll.smoothScrollTo(0, (relativeY - offset).coerceAtLeast(0)) }
                    }
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    attachToChildren(view.getChildAt(i))
                }
            }
        }

        // Run attachment on the scroll's child (content) to compute coordinates correctly
        val content = scroll.getChildAt(0)
        if (content != null) attachToChildren(content)
        else attachToChildren(root)
    }

    // Disable scrolling by default and enable only when keyboard is visible
    private fun setupKeyboardScrollControl(root: View) {
        val scroll = root.findViewById<NestedScrollView?>(com.example.myapplication.R.id.scroll_auth) ?: return

        // Disable scrolling initially
        scroll.isNestedScrollingEnabled = false

        // Listen for keyboard visibility changes
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            try {
                val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
                // Enable scrolling when keyboard is visible, disable when hidden
                scroll.isNestedScrollingEnabled = imeVisible
            } catch (_: Exception) {
                // ignore
            }
            insets
        }
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
    protected fun navigateWithDelay(actionId: Int, args: Bundle? = null, delayMs: Long = 300L, navOptions: androidx.navigation.NavOptions? = null) {
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
