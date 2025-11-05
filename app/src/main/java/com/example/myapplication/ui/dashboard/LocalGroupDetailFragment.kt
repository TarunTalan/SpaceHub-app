package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.example.myapplication.ui.common.BaseFragment

class LocalGroupDetailFragment : BaseFragment(R.layout.fragment_local_group_detail) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val id = args.getInt("id")
        val name = args.getString("name") ?: ""
        val imageUrl = args.getString("imageUrl")

        view.findViewById<TextView>(R.id.title)?.text = name
        view.findViewById<TextView>(R.id.subtitle)?.text = "Local Group ID: $id"
        val img = view.findViewById<ImageView>(R.id.img)
        if (!imageUrl.isNullOrBlank()) {
            Glide.with(this).load(imageUrl).placeholder(R.drawable.default_profile).into(img)
        } else {
            img?.setImageResource(R.drawable.default_profile)
        }
    }
}
