package com.example.myapplication.ui.common

import android.content.Context
import android.net.Uri
import android.widget.ImageView
import com.bumptech.glide.Glide
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
                    if (s.startsWith("content://") || s.startsWith("file://")) {
                        val uri = try { s.toUri() } catch (_: Exception) { null }
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
                        val f = File(s)
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
                        .load(s.ifBlank { null } ?: R.drawable.default_profile)
                        .placeholder(R.drawable.default_profile)
                        .error(R.drawable.default_profile)
                        .circleCrop()
                        .into(imageView)
                }
                else -> {
                    val s = image.toString()
                    Glide.with(context)
                        .load(s.ifBlank { null } ?: R.drawable.default_profile)
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
