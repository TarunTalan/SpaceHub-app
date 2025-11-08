package com.example.myapplication.ui.group

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatButton
import androidx.navigation.fragment.findNavController
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.R

class CreateCommunityOrGroupFragment: BaseFragment(R.layout.fragment_create_community_or_group) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Buttons in the layout
        val createGroupBtn = view.findViewById<AppCompatButton>(R.id.create_group)
        val createCommBtn = view.findViewById<AppCompatButton>(R.id.create_comm)
        val nextBtn = view.findViewById<View>(R.id.btn_join_community)

        // Track currently selected button (null = none)
        var selectedBtn: AppCompatButton? = null

        fun applySelected(selected: AppCompatButton?) {
            // reset both to default
            try {
                createGroupBtn.background = AppCompatResources.getDrawable(requireContext(), R.drawable.rounded_button_bg_gray)
            } catch (_: Exception) {}
            try {
                createCommBtn.background = AppCompatResources.getDrawable(requireContext(), R.drawable.rounded_button_bg_gray)
            } catch (_: Exception) {}

            // apply outline to selected
            if (selected != null) {
                try {
                    selected.background = AppCompatResources.getDrawable(requireContext(), R.drawable.rounded_button_bg_gray_outline)
                } catch (_: Exception) {}
            }
        }

        // Click toggles selection (selectable, not immediate navigation)
        createGroupBtn?.setOnClickListener {
            if (selectedBtn == createGroupBtn) {
                // deselect
                selectedBtn = null
            } else {
                selectedBtn = createGroupBtn
            }
            applySelected(selectedBtn)
        }

        createCommBtn?.setOnClickListener {
            if (selectedBtn == createCommBtn) {
                selectedBtn = null
            } else {
                selectedBtn = createCommBtn
            }
            applySelected(selectedBtn)
        }

        nextBtn?.setOnClickListener {
            // Navigate according to selection
            when (selectedBtn) {
                createGroupBtn -> {
                    try {
                        findNavController().navigate(R.id.action_createCommunityOrGroupFragment_to_createGroupFragment)
                    } catch (_: Exception) {}
                }
                createCommBtn -> {
                    try {
                        findNavController().navigate(R.id.action_createCommunityOrGroupFragment_to_createCommunityFragment)
                    } catch (_: Exception) {}
                }
                else -> {
                    Toast.makeText(requireContext(), "Please select Create Group or Create Community", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}