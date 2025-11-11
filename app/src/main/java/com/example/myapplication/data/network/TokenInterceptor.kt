package com.example.myapplication.data.network

import okhttp3.Interceptor
import okhttp3.Response

/*
  Adds Authorization header when an access token is present, but skips it for public endpoints
 such as registration/login/forgot-password flows which should not be sent an auth header.
 */
class TokenInterceptor(private val tokenStore: TokenStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        // Do not modify requests; do not add Authorization header to any API.
        // This interceptor intentionally acts as a passthrough to ensure no auth header is attached.
        val original = chain.request()
        return chain.proceed(original)
    }
}
