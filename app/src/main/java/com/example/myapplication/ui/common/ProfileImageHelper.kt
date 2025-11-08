package com.example.myapplication.ui.common

import android.content.Context
import android.net.Uri
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.example.myapplication.BuildConfig
import com.example.myapplication.R
import java.io.File
import androidx.core.net.toUri

object ProfileImageHelper {
    /**
     * Load a profile image into an ImageView.
     * Accepts URL string (preferred), or resource fallback. Local file/uri still supported for flexibility.
     */
    fun loadProfileImageIntoView(context: Context, imageView: ImageView?, image: Any?) {
        if (imageView == null) return

        try {
            when (image) {
                null -> {
                    Glide.with(context)
                        .load(R.drawable.default_profile)
                        .placeholder(R.drawable.default_profile)
                        .error(R.drawable.default_profile)
                        .circleCrop()
                        .into(imageView)
                }
                is Int -> {
                    Glide.with(context)
                        .load(image)
                        .placeholder(R.drawable.default_profile)
                        .error(R.drawable.default_profile)
                        .circleCrop()
                        .into(imageView)
                }
                is File -> {
                    Glide.with(context)
                        .load(image)
                        .placeholder(R.drawable.default_profile)
                        .error(R.drawable.default_profile)
                        .circleCrop()
                        .into(imageView)
                }
                is Uri -> {
                    Glide.with(context)
                        .load(image)
                        .placeholder(R.drawable.default_profile)
                        .error(R.drawable.default_profile)
                        .circleCrop()
                        .into(imageView)
                }
                is String -> {
                    val s = image.trim()

                    // Handle relative URLs from API (e.g., "avatars/user_email/profile.png")
                    val base = BuildConfig.BASE_URL.trimEnd('/')
                    val finalUrl = when {
                        s.isBlank() -> s
                        s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true) -> s
                        s.startsWith("content://") || s.startsWith("file://") -> s
                        else -> {
                            // treat as relative path
                            val clean = s.trimStart('/')
                            "$base/$clean"
                        }
                    }

                    // Use Uri for URLs with query params (signed URLs) to preserve query string
                    if (finalUrl.contains("?") || finalUrl.contains("X-Amz-")) {
                        try {
                            val uri = Uri.parse(finalUrl)
                            Glide.with(context)
                                .load(uri)
                                .placeholder(R.drawable.default_profile)
                                .error(R.drawable.default_profile)
                                .circleCrop()
                                .into(imageView)
                            return
                        } catch (_: Exception) { }
                    }

                    if (finalUrl.startsWith("content://") || finalUrl.startsWith("file://")) {
                        val uri = try { finalUrl.toUri() } catch (_: Exception) { null }
                        if (uri != null) {
                            Glide.with(context)
                                .load(uri)
                                .placeholder(R.drawable.default_profile)
                                .error(R.drawable.default_profile)
                                .circleCrop()
                                .into(imageView)
                            return
                        }
                    }
                    try {
                        val f = File(finalUrl)
                        if (f.exists()) {
                            Glide.with(context)
                                .load(f)
                                .placeholder(R.drawable.default_profile)
                                .error(R.drawable.default_profile)
                                .circleCrop()
                                .into(imageView)
                            return
                        }
                    } catch (_: Exception) {}

                    Glide.with(context)
                        .load(finalUrl.ifBlank { null } ?: R.drawable.default_profile)
                        .placeholder(R.drawable.default_profile)
                        .error(R.drawable.default_profile)
                        .circleCrop()
                        .into(imageView)
                }
                else -> {
                    val s = image.toString()
                    val finalUrl = if (s.startsWith("avatars/") || s.startsWith("/avatars/")) {
                        val cleanPath = s.trimStart('/')
                        "${BuildConfig.BASE_URL}/$cleanPath"
                    } else {
                        s
                    }

                    Glide.with(context)
                        .load(finalUrl.ifBlank { null } ?: R.drawable.default_profile)
                        .placeholder(R.drawable.default_profile)
                        .error(R.drawable.default_profile)
                        .circleCrop()
                        .into(imageView)
                }
            }
        } catch (_: Exception) {
            try {
                Glide.with(context)
                    .load(R.drawable.default_profile)
                    .placeholder(R.drawable.default_profile)
                    .error(R.drawable.default_profile)
                    .circleCrop()
                    .into(imageView)
            } catch (_: Exception) {}
        }
    }
}
