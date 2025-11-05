package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.viewpager2.widget.ViewPager2
import com.example.myapplication.R
import com.example.myapplication.ui.common.BaseFragment
import androidx.navigation.fragment.findNavController
import android.animation.ValueAnimator
import android.widget.EditText
import androidx.fragment.app.activityViewModels

class SearchFragment: BaseFragment(R.layout.fragment_search) {
    private val vm: SearchSharedViewModel by activityViewModels()
    private var previousSoftInputMode: Int? = null
    private var pageCallback: ViewPager2.OnPageChangeCallback? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<ImageView>(R.id.back)?.setOnClickListener {
            runCatching {
                val nav = findNavController()
                val popped = runCatching { nav.popBackStack(R.id.dashboardFragment, false) }.getOrNull() == true
                if (!popped) runCatching { nav.navigate(R.id.action_searchFragment_to_dashboardFragment) }
            }
        }

        val communityTab = view.findViewById<TextView>(R.id.communityTab)
        val localTab = view.findViewById<TextView>(R.id.localGroupsTab)
        val indicator = view.findViewById<View>(R.id.bottomNavIndicator)
        val pager = view.findViewById<ViewPager2>(R.id.viewPager)
        val etSearch = view.findViewById<EditText>(R.id.etSearch)
        val submitIcon = view.findViewById<ImageView>(R.id.submitSearch)

        pager.adapter = SearchTabsAdapter(this)

        fun positionIndicator(centerX: Float, width: Int, animate: Boolean) {
            indicator?.let { ind ->
                // Use translationX so ConstraintLayout doesn't fight with position
                val targetX = centerX - ind.width / 2f
                if (animate) {
                    ind.animate().translationX(targetX).setDuration(200).start()
                    val startW = ind.width
                    if (startW != width) {
                        ValueAnimator.ofInt(startW, width).apply {
                            duration = 200
                            addUpdateListener {
                                val w = it.animatedValue as Int
                                ind.layoutParams = ind.layoutParams.apply { this.width = w }
                                ind.requestLayout()
                            }
                        }.start()
                    }
                } else {
                    ind.translationX = targetX
                    if (ind.width != width) {
                        ind.layoutParams = ind.layoutParams.apply { this.width = width }
                        ind.requestLayout()
                    }
                }
            }
        }

        fun updateSelectedStyles(selected: TextView, other: TextView) {
            selected.alpha = 1f
            other.alpha = 0.6f
        }

        view.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                indicator?.alpha = 0f
                indicator?.scaleX = 0f
                val center = communityTab.x
                positionIndicator(center, communityTab.width, animate = false)
                indicator?.pivotX = indicator.width / 2f
                indicator?.animate()?.alpha(1f)?.scaleX(1f)?.setDuration(250)?.start()
                updateSelectedStyles(communityTab, localTab)
            }
        })

        // Clicking tabs changes the pager. Also animate indicator immediately for snappy UI.
        communityTab.setOnClickListener {
            pager.currentItem = 0
            updateSelectedStyles(communityTab, localTab)
            val center = communityTab.x
            positionIndicator(center, communityTab.width, animate = true)
        }
        localTab.setOnClickListener {
            pager.currentItem = 1
            updateSelectedStyles(localTab, communityTab)
            val center = localTab.x
            positionIndicator(center, localTab.width, animate = true)
        }

        // Ensure the indicator moves when the user swipes pages as well
        pageCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val selected = if (position == 0) communityTab else localTab
                val other = if (position == 0) localTab else communityTab
                updateSelectedStyles(selected, other)
                val center = selected.x
                positionIndicator(center, selected.width, animate = true)
            }
        }
        pager.registerOnPageChangeCallback(pageCallback!!)

        fun findTabFragmentByType(index: Int): androidx.fragment.app.Fragment? {
            return childFragmentManager.fragments.firstOrNull {
                when (index) {
                    0 -> it is CommunityTabFragment
                    else -> it is LocalGroupsTabFragment
                }
            }
        }

        fun updateCommunityList(items: List<com.example.myapplication.ui.dashboard.adapter.CommunityUi>) {
            (findTabFragmentByType(0) as? CommunityTabFragment)?.let { frag ->
                frag.view?.post { if (frag.isAdded) frag.submitList(items) }
            }
        }

        fun triggerSearch() {
            val query = etSearch.text?.toString()?.trim().orEmpty()
            val commTab = findTabFragmentByType(0) as? CommunityTabFragment
            commTab?.setLoading(true)
            vm.setQuery(query)
            vm.search(query) { list ->
                updateCommunityList(list)
                commTab?.setLoading(false)
            }
        }

        // IME action search on keyboard
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                triggerSearch()
                true
            } else false
        }

        // Right-side submit icon click
        submitIcon?.setOnClickListener { triggerSearch() }

        // Initial empty list
        updateCommunityList(emptyList())
    }

    override fun onResume() {
        super.onResume()
        // Pin the layout so it doesn't move with the keyboard
        val window = activity?.window ?: return
        previousSoftInputMode = window.attributes?.softInputMode
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
    }

    override fun onPause() {
        super.onPause()
        // Restore the previous soft input mode for other screens
        val window = activity?.window ?: return
        previousSoftInputMode?.let { window.setSoftInputMode(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // unregister page callback to avoid leaks
        pageCallback?.let { view?.findViewById<ViewPager2>(R.id.viewPager)?.unregisterOnPageChangeCallback(it) }
        pageCallback = null
    }
}