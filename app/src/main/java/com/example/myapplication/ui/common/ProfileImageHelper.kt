package com.example.myapplication.ui.common

import android.content.Context
import android.net.Uri
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.example.myapplication.R
import java.io.File
import androidx.core.net.toUri

object ProfileImageHelper {
    /**
     * Load a profile image into an ImageView.
     * Accepts: nullable String (remote URL or URI), File (local file), Uri, Int (resource id), or null.
     * For local files we use an ObjectKey based on lastModified() to avoid stale cache.
     * For remote URLs we use a prefs-stored timestamp "profile_image_updated_at" as signature to bust cache when updated.
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
                    val key = try { ObjectKey(image.absolutePath + "-" + image.lastModified()) } catch (_: Exception) { ObjectKey(image.absolutePath) }
                    Glide.with(context)
                        .load(image)
                        .signature(key)
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
                    // If it's a content/file URI string, parse to Uri so Glide handles it properly
                    if (s.startsWith("content://") || s.startsWith("file://")) {
                        val uri = try {
                            s.toUri() } catch (_: Exception) { null }
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

                    // If string points to an existing file path, load as File with signature
                    try {
                        val f = File(s)
                        if (f.exists()) {
                            val key = try { ObjectKey(f.absolutePath + "-" + f.lastModified()) } catch (_: Exception) { ObjectKey(f.absolutePath) }
                            Glide.with(context)
                                .load(f)
                                .signature(key)
                                .placeholder(R.drawable.default_profile)
                                .error(R.drawable.default_profile)
                                .circleCrop()
                                .into(imageView)
                            return
                        }
                    } catch (_: Exception) {}

                    // If it's an HTTP(S) URL, use preferences-based timestamp signature to bust cache when updated
                    if (s.startsWith("http://") || s.startsWith("https://")) {
                        try {
                            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                            val ts = prefs.getLong("profile_image_updated_at", 0L)
                            val key = ObjectKey(ts)
                            Glide.with(context)
                                .load(s)
                                .signature(key)
                                .placeholder(R.drawable.default_profile)
                                .error(R.drawable.default_profile)
                                .circleCrop()
                                .into(imageView)
                            return
                        } catch (_: Exception) {}
                    }

                    // Otherwise treat as remote URL/other string
                    Glide.with(context)
                        .load(s.ifBlank { null } ?: R.drawable.default_profile)
                        .placeholder(R.drawable.default_profile)
                        .error(R.drawable.default_profile)
                        .circleCrop()
                        .into(imageView)
                }
                else -> {
                    // Fallback: try toString()
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
