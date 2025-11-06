package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.navigation.fragment.findNavController
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.R

class ChatRoomFragment: BaseFragment(R.layout.fragment_chat_room) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Navigate to search friends when clicking add person icon
        view.findViewById<ImageView>(R.id.ivAddPerson)?.setOnClickListener {
            findNavController().navigate(R.id.action_chatRoomFragment_to_searchFriendsFragment)
        }
    }
}