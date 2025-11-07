package com.example.myapplication.ui.common

import android.content.Context
import android.util.Log
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
                val url = original.url.toString()

                Log.d("AuthGlideModule", "Intercepting request to: $url")

                // Only add auth header for our API endpoints
                val needsAuth = url.contains("codewithketan.me") ||
                                url.contains("/api/") ||
                                url.contains("/avatars/") ||
                                url.contains("/uploads/")

                if (needsAuth) {
                    val token = SharedPrefsTokenStore(context).getAccessToken()
                    Log.d("AuthGlideModule", "URL needs auth: $needsAuth, Token present: ${!token.isNullOrEmpty()}")

                    val request = if (!token.isNullOrEmpty()) {
                        Log.d("AuthGlideModule", "Adding Bearer token to request")
                        original.newBuilder()
                            .header("Authorization", "Bearer $token")
                            .build()
                    } else {
                        Log.w("AuthGlideModule", "No token available!")
                        original
                    }
                    chain.proceed(request)
                } else {
                    Log.d("AuthGlideModule", "URL does not need auth, proceeding normally")
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
