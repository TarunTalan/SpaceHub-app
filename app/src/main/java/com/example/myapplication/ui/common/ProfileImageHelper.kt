package com.example.myapplication.ui.common

import android.content.Context
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.example.myapplication.R

object ProfileImageHelper {
    fun loadProfileImageIntoView(context: Context, imageView: ImageView?, imageUrl: String?) {
        if (imageView == null) return
        val url = imageUrl?.takeIf { it.isNotBlank() }
        Glide.with(context)
            .load(url ?: R.drawable.default_profile)
            .placeholder(R.drawable.default_profile)
            .error(R.drawable.default_profile)
            .circleCrop()
            .into(imageView)
    }
}

