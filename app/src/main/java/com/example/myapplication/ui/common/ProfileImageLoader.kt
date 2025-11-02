package com.example.myapplication.ui.common

import android.content.Context
import android.widget.ImageView
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.myapplication.BuildConfig
import com.example.myapplication.R
import com.example.myapplication.data.user.UserDataManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/**
 * Helper class to load profile images from DataStore using Flow.
 * Automatically updates when the profile image changes.
 *
 * Handles all image sources in priority order:
 * 1. Server URL (from profile_image_url)
 * 2. Local file path (from profile_image_path)
 * 3. Content URI (from profile_image_uri)
 * 4. Drawable resource (from profile_image_res)
 */
object ProfileImageLoader {

    /**
     * Load profile image into ImageView with automatic updates.
     * Observes all profile image sources and loads the best available one.
     *
     * @param imageView Target ImageView
     * @param context Context for Glide
     * @param lifecycleOwner For lifecycle-aware Flow collection
     * @param circular Apply circular crop (default true for profile pics)
     */
    fun loadProfileImage(
        imageView: ImageView,
        context: Context,
        lifecycleOwner: LifecycleOwner,
        circular: Boolean = true
    ) {
        val userDataManager = UserDataManager.getInstance(context)

        // Combine all profile image sources and load the best available one
        lifecycleOwner.lifecycleScope.launch {
            combine(
                userDataManager.profileImageUrlFlow,
                userDataManager.profileImagePathFlow,
                userDataManager.profileImageUriFlow,
                userDataManager.profileImageResFlow
            ) { url, path, uri, res ->
                // Priority: URL > Path > URI > Resource
                when {
                    !url.isNullOrBlank() -> {
                        val cleanUrl = url.trim()
                        // If it's already a full URL, use it as-is
                        if (cleanUrl.startsWith("http://", ignoreCase = true) ||
                            cleanUrl.startsWith("https://", ignoreCase = true)) {
                            cleanUrl
                        } else {
                            // Remove leading slash to avoid double slash, ensure BASE_URL ends with /
                            val baseUrl = BuildConfig.BASE_URL.trimEnd('/') + "/"
                            val relativePath = cleanUrl.trimStart('/')
                            "${baseUrl}uploads/$relativePath"
                        }
                    }
                    !path.isNullOrBlank() && File(path).exists() -> File(path)
                    !uri.isNullOrBlank() -> uri.toUri()
                    res != null && res != 0 -> res
                    else -> R.drawable.default_profile
                }
            }.collect { source ->
                val request = Glide.with(context)
                    .load(source)
                    .placeholder(R.drawable.default_profile)
                    .error(R.drawable.default_profile)

                if (circular) {
                    request.circleCrop().into(imageView)
                } else {
                    request.into(imageView)
                }
            }
        }
    }
}

