package com.example.myapplication.ui.common

import android.content.Context
import android.widget.ImageView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.myapplication.BuildConfig
import com.example.myapplication.R
import com.example.myapplication.data.user.UserDataManager
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

object ProfileImageLoader {

    /**
     * Load profile image into ImageView with automatic updates using only stored URL.
     * If URL is relative, constructs full path from BASE_URL.
     */
    fun loadProfileImage(
        imageView: ImageView,
        context: Context,
        lifecycleOwner: LifecycleOwner,
        circular: Boolean = true
    ) {
        val userDataManager = UserDataManager.getInstance(context)

        lifecycleOwner.lifecycleScope.launch {
            userDataManager.profileImageUrlFlow
                .map { url ->
                    val u = url?.trim().orEmpty()
                    when {
                        u.isBlank() -> R.drawable.default_profile
                        u.startsWith("http://", true) || u.startsWith("https://", true) -> u
                        else -> BuildConfig.BASE_URL.trimEnd('/') + "/" + u.trimStart('/')
                    }
                }
                .collect { source ->
                    val req = Glide.with(context)
                        .load(source)
                        .placeholder(R.drawable.default_profile)
                        .error(R.drawable.default_profile)
                    if (circular) req.circleCrop().into(imageView) else req.into(imageView)
                }
        }
    }
}
