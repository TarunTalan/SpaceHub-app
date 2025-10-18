package com.example.myapplication.data.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds Authorization header when an access token is present, but skips it for public endpoints
 * such as registration/login/OTP validation which should not be sent an auth header.
 */
class TokenInterceptor(private val tokenStore: TokenStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // Skip adding Authorization header for unauthenticated endpoints
        val path = original.url.encodedPath
        // Include all public endpoints that must not receive Authorization header
        val unauthEndpoints = listOf(
            "registration",
            "login",
            "validateregisterotp",
            "forgotpassword",
            "validateforgototp",
            "resetpassword",
            // Resend OTP endpoints use session tokens in the request body and should not be sent the access token
            "resendsignupotp",
            "resendforgototp"
        )
        val isUnauthEndpoint = unauthEndpoints.any { path.contains(it, ignoreCase = true) }

        val token = tokenStore.getAccessToken()
        val tokenPresent = !token.isNullOrBlank()

        val newReq = if (!isUnauthEndpoint && tokenPresent) {
            // Add Authorization header for authenticated endpoints
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            // Skip adding the header for unauthenticated endpoints
            original
        }

        return chain.proceed(newReq)
    }
}
