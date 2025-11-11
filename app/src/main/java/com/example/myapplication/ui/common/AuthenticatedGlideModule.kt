package com.example.myapplication.ui.common

import android.content.Context
import android.util.Log
import android.net.Uri
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import java.io.InputStream
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import okhttp3.OkHttpClient

@GlideModule
class AuthenticatedGlideModule : AppGlideModule() {

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        Log.d("AuthGlideModule", "Registering authenticated Glide components")

        // Create OkHttpClient without adding Authorization header
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val url = original.url
                val urlStr = url.toString()

                Log.d("AuthGlideModule", "Intercepting Glide request to: $urlStr")

                // Determine API host from BASE_URL (safe fallback to codewithketan.me)
                val apiHost = try {
                    Uri.parse(com.example.myapplication.BuildConfig.BASE_URL).host
                } catch (_: Exception) {
                    "codewithketan.me"
                }

                val requestHost = try { url.host } catch (_: Exception) { null }
                val requestPath = try { url.encodedPath } catch (_: Exception) { "" }

                // Only log whether request is to our backend; do not add auth header
                val isBackend = when {
                    requestHost == null -> false
                    requestHost.contains(apiHost ?: "codewithketan.me", ignoreCase = true) -> true
                    requestPath.startsWith("/api/") -> true
                    else -> false
                }

                if (isBackend) {
                    Log.d("AuthGlideModule", "Glide request to backend host=$requestHost path=$requestPath (no auth will be added)")
                } else {
                    Log.d("AuthGlideModule", "Glide request to external host=$requestHost path=$requestPath")
                }

                chain.proceed(original)
            }
            .build()

        Log.d("AuthGlideModule", "Replacing Glide's default HTTP loader with authenticated version")
        // Replace Glide's default HTTP loader with our authenticated version
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpUrlLoader.Factory(client)
        )
    }

    override fun isManifestParsingEnabled(): Boolean {
        // Disable manifest parsing for performance
        return false
    }
}
