package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.view.ViewTreeObserver
import androidx.viewpager2.widget.ViewPager2
import com.example.myapplication.R
import com.example.myapplication.ui.common.BaseFragment
import androidx.navigation.fragment.findNavController
import android.animation.ValueAnimator
import androidx.core.widget.addTextChangedListener
import android.widget.EditText
import androidx.fragment.app.activityViewModels
import com.example.myapplication.ui.dashboard.adapter.CommunityUi

class SearchFragment: BaseFragment(R.layout.fragment_search) {
    private val vm: SearchSharedViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Back navigation
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

        // Setup pager
        pager.adapter = SearchTabsAdapter(this)

        fun positionIndicator(centerX: Float, width: Int, animate: Boolean) {
            indicator?.let { ind ->
                if (animate) {
                    ind.animate().x(centerX - ind.width / 2f).setDuration(200).start()
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
                    ind.x = centerX - ind.width / 2f
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

        // Initial placement when layout is ready
        view.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                indicator?.alpha = 0f
                indicator?.scaleX = 0f
                val center = communityTab.x + communityTab.width / 2f
                positionIndicator(center, communityTab.width, animate = false)
                indicator?.pivotX = indicator.width / 2f
                indicator?.animate()?.alpha(1f)?.scaleX(1f)?.setDuration(250)?.start()
                updateSelectedStyles(communityTab, localTab)
            }
        })

        // Click listeners -> change page
        communityTab.setOnClickListener { pager.currentItem = 0 }
        localTab.setOnClickListener { pager.currentItem = 1 }

        fun findTabFragment(index: Int): androidx.fragment.app.Fragment? {
            return childFragmentManager.findFragmentByTag("f$index") ?: run {
                // ViewPager2 tags fragments differently; fallback by iterating
                childFragmentManager.fragments.getOrNull(index)
            }
        }

        fun submitFiltered() {
            val listCom = vm.filterCommunities()
            val listLocal = vm.filterLocalGroups()
            (childFragmentManager.findFragmentByTag("f0") as? CommunityTabFragment)?.submitList(listCom)
            (childFragmentManager.findFragmentByTag("f1") as? LocalGroupsTabFragment)?.submitList(listLocal)
        }

        // Sync indicator on page scroll
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                val leftTab = if (position == 0) communityTab else localTab
                val rightTab = if (position == 0) localTab else communityTab
                val leftCenter = leftTab.x + leftTab.width / 2f
                val rightCenter = rightTab.x + rightTab.width / 2f
                val center = leftCenter + (rightCenter - leftCenter) * positionOffset
                val width = (leftTab.width + (rightTab.width - leftTab.width) * positionOffset).toInt()
                positionIndicator(center, width, animate = false)
            }
            override fun onPageSelected(position: Int) {
                if (position == 0) updateSelectedStyles(communityTab, localTab) else updateSelectedStyles(localTab, communityTab)
            }
        })

        // Mock data source for now (replace with repo calls)
        if (vm.allCommunities.value.isEmpty() && vm.allLocalGroups.value.isEmpty()) {
            val demo = listOf(
                CommunityUi(1, "Android Community", null, "42k members"),
                CommunityUi(2, "Kotlin Lovers", null, "15k members"),
                CommunityUi(3, "Space Hub", null, "8k members")
            )
            val demoLocal = listOf(
                CommunityUi(101, "Local Android Devs", null, "1k members", isLocal = true),
                CommunityUi(102, "Bengaluru Kotlin", null, "2k members", isLocal = true)
            )
            vm.setSource(demo, demoLocal)
        }

        // Initial submit
        submitFiltered()

        // Search box wiring
        etSearch.addTextChangedListener { text ->
            vm.setQuery(text?.toString().orEmpty())
            submitFiltered()
        }
    }
}