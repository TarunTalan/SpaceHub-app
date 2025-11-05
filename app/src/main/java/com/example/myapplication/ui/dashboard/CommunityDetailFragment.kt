package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.example.myapplication.ui.common.BaseFragment

class CommunityDetailFragment : BaseFragment(R.layout.fragment_community_detail) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val id = args.getInt("id")
        val name = args.getString("name") ?: ""
        val imageUrl = args.getString("imageUrl")

        view.findViewById<TextView>(R.id.title)?.text = name
        view.findViewById<TextView>(R.id.subtitle)?.text = "Community ID: $id"
        val img = view.findViewById<ImageView>(R.id.img)
        // Only attempt to load when imageUrl is non-null/non-blank
        if (!imageUrl.isNullOrBlank()) {
            Glide.with(this)
                .load(imageUrl)
                .placeholder(R.drawable.default_profile)
                .error(R.drawable.default_profile)
                .circleCrop()
                .into(img)
        } else {
            img?.setImageResource(R.drawable.default_profile)
        }
    }
}
