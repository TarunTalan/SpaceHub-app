package com.example.myapplication.ui.common

import android.content.Context
import android.util.Log
import android.net.Uri
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import com.example.myapplication.data.network.SharedPrefsTokenStore
import java.io.InputStream
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import okhttp3.OkHttpClient

@GlideModule
class AuthenticatedGlideModule : AppGlideModule() {

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        Log.d("AuthGlideModule", "Registering authenticated Glide components")

        // Create OkHttpClient with auth interceptor
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

                // Only add auth header for our backend API host or requests under /api/ path
                val needsAuth = when {
                    requestHost == null -> false
                    requestHost.contains(apiHost ?: "codewithketan.me", ignoreCase = true) -> true
                    requestPath.startsWith("/api/") -> true
                    else -> false
                }

                if (needsAuth) {
                    val token = SharedPrefsTokenStore(context).getAccessToken()
                    Log.d("AuthGlideModule", "URL needs auth: $needsAuth, Token present: ${!token.isNullOrEmpty()}")

                    val request = if (!token.isNullOrEmpty()) {
                        Log.d("AuthGlideModule", "Adding Bearer token to Glide request for host=$requestHost")
                        original.newBuilder()
                            .header("Authorization", "Bearer $token")
                            .build()
                    } else {
                        Log.w("AuthGlideModule", "No token available for Glide request")
                        original
                    }
                    chain.proceed(request)
                } else {
                    Log.d("AuthGlideModule", "URL does not need auth (skipping): host=$requestHost path=$requestPath")
                    chain.proceed(original)
                }
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
