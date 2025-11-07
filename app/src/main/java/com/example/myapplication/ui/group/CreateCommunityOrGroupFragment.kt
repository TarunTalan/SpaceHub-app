package com.example.myapplication.ui.group

import android.os.Bundle
import android.view.View
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.R
import androidx.navigation.fragment.findNavController

class CreateCommunityOrGroupFragment: BaseFragment(R.layout.fragment_create_community_or_group) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Buttons in the layout
        val createGroupBtn = view.findViewById<View>(R.id.create_group)
        val createCommBtn = view.findViewById<View>(R.id.create_comm)

        createGroupBtn?.setOnClickListener {
            try {
                // Navigate to the group name & picture fragment
                findNavController().navigate(R.id.groupNamePicFragment)
            } catch (_: Exception) {}
        }

        createCommBtn?.setOnClickListener {
            try {
                // Navigate to the community name & picture fragment
                findNavController().navigate(R.id.communityNamePicFragment)
            } catch (_: Exception) {}
        }
    }
}