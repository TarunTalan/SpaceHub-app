package com.example.myapplication.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.ui.common.BaseFragment

class CommunityNamePicFragment : BaseFragment(R.layout.fragment_community_name_pic) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Back arrow - navigate back to CreateCommunityFragment
        try {
            val backArrow = view.findViewById<ImageView>(R.id.back_arrow)
            backArrow?.setOnClickListener {
                try {
                    findNavController().navigateUp()
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }
}
