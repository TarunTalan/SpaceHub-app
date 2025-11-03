package com.example.myapplication.ui.dashboard

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.myapplication.ui.dashboard.CommunityTabFragment
import com.example.myapplication.ui.dashboard.LocalGroupsTabFragment

class SearchTabsAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
    override fun getItemCount(): Int = 2
    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> CommunityTabFragment()
        else -> LocalGroupsTabFragment()
    }
}
