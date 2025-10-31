package com.example.myapplication.ui.dashboard

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatButton
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.edit
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.ui.common.BaseFragment

class CreateCommunityFragment : BaseFragment(R.layout.fragment_create_community) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Find the container that holds the community buttons
        val btnContainer = view.findViewById<ConstraintLayout>(R.id.communityBtnLayout)
        var selectedBtn: AppCompatButton? = null
        if (btnContainer != null) {
            // Iterate child views and attach click listener to buttons
            for (i in 0 until btnContainer.childCount) {
                val child = btnContainer.getChildAt(i)
                if (child is AppCompatButton) {
                    child.setOnClickListener {
                        try {
                            // update selection UI: mark this as selected and unmark previous
                            selectedBtn?.background = AppCompatResources.getDrawable(requireContext(), R.drawable.rounded_button_bg_gray)
                            child.background = AppCompatResources.getDrawable(requireContext(), R.drawable.rounded_button_bg_gray_outline)
                            selectedBtn = child
                        } catch (_: Exception) { }
                        try {
                            findNavController().navigate(R.id.action_createCommunityFragment_to_communityNamePicFragment)
                        } catch (_: Exception) { }
                    }
                }
            }
        }

        try {
            val joinBtn = view.findViewById<AppCompatButton?>(R.id.btn_join_community)
            joinBtn?.setOnClickListener {
                try {
                    findNavController().navigate(R.id.action_createCommunityFragment_to_joinCommunityFragment)
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }
}