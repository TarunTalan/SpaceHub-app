package com.example.myapplication.ui.group

import android.os.Bundle
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatButton
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.ui.common.BaseFragment

class CreateGroupFragment : BaseFragment(R.layout.fragment_create_group) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnContainer = view.findViewById<ConstraintLayout>(R.id.groupLayoutBtns)
        var selectedBtn: AppCompatButton? = null
        if (btnContainer != null) {
            for (i in 0 until btnContainer.childCount) {
                val child = btnContainer.getChildAt(i)
                if (child is AppCompatButton) {
                    child.setOnClickListener {
                        try {
                            selectedBtn?.background = AppCompatResources.getDrawable(requireContext(), R.drawable.rounded_button_bg_gray)
                            child.background = AppCompatResources.getDrawable(requireContext(), R.drawable.rounded_button_bg_gray_outline)
                            selectedBtn = child
                        } catch (_: Exception) { }
                        try {
                            findNavController().navigate(R.id.groupNamePicFragment)
                        } catch (_: Exception) { }
                    }
                }
            }
        }

        try {
            val joinBtn = view.findViewById<AppCompatButton?>(R.id.btn_join_group)
            joinBtn?.setOnClickListener {
                try {
                    // Navigate to joinGroupFragment (group-specific join flow)
                    findNavController().navigate(R.id.joinGroupFragment)
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }
}
