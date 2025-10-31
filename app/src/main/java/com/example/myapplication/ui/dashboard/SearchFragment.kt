package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import com.example.myapplication.R
import com.example.myapplication.ui.common.BaseFragment
import androidx.navigation.fragment.findNavController

class SearchFragment: BaseFragment(R.layout.fragment_search) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            val back = view.findViewById<ImageView>(R.id.back)
            back?.setOnClickListener {
                try {
                    val nav = findNavController()
                    val popped = try { nav.popBackStack(R.id.dashboardFragment, false) } catch (_: Exception) { false }
                    if (!popped) {
                        try { nav.navigate(R.id.action_searchFragment_to_dashboardFragment) } catch (_: Exception) { }
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }
}