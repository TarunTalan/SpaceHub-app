package com.example.myapplication.data.network

import android.content.Context
import com.example.myapplication.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkModule {
    private fun baseUrl(): String {
        val raw = BuildConfig.BASE_URL
        return if (raw.endsWith('/')) raw else "$raw/"
    }

    private fun createOkHttpClient(context: Context): OkHttpClient {
        val tokenStore = SharedPrefsTokenStore(context)
        // Configure HTTP logging: enabled at BODY level only when BuildConfig.DEBUG is true
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        }

        // Avoid printing sensitive headers even in debug logs
        if (BuildConfig.DEBUG) {
            try {
                logging.redactHeader("Authorization")
                logging.redactHeader("Cookie")
            } catch (_: Exception) { /* ignore if method unavailable */ }
        }

        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(TokenInterceptor(tokenStore))

        // Always add the logging interceptor; its level controls output based on BuildConfig.DEBUG
        builder.addInterceptor(logging)

        return builder.build()
    }

    fun createRetrofit(context: Context): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl())
            .client(createOkHttpClient(context))
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    fun createApiService(context: Context): ApiService =
        createRetrofit(context).create(ApiService::class.java)
}
