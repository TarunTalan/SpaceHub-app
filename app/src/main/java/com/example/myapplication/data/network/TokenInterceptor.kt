package com.example.myapplication.data.network

import okhttp3.Interceptor
import okhttp3.Response

/*
  Adds Authorization header when an access token is present, but skips it for public endpoints
 such as registration/login/forgot-password flows which should not be sent an auth header.
 */
class TokenInterceptor(private val tokenStore: TokenStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val path = original.url.encodedPath

        // Skip adding Authorization header for unauthenticated endpoints
        val unauthEndpoints = listOf(
            "registration",
            "login",
            "forgotpassword",
            "validateforgototp",
            "resetpassword",
            "resendforgototp",
            "profile/getProfile",
            "community/my-communities",
            "community/create",
            "profile/updateProfile",
            "profile/avatar",
            "community/members",
            "community/changeRole",
            "community/removeMember",
            "community/leave",
            "community/updateInfo",
            "community/{communityId}/upload-banner",
            "community/delete",
            "community/my-communities",
            "community/blockMember",
            "community/requestJoin",
            "community/{communityId}/rooms/create",
            "community/{communityId}/rooms/{roomId}",
            "community/{communityId}/rooms/all",
            "community/{communityId}/rooms/{roomId}/rename",
            "community/search",
            "community/my-pending-requests",
            "community/rejectRequest",
            "community/acceptRequest",
            "community/invites/{communityId}/create",
            "community/invites/accept",
            "new-chatroom/create",
            "rooms/all",
            "friends/request",
            "friends/respond",
            "friends/list",
            "friends/pending/incoming",
            "search"

        )
        val isUnauthEndpoint = unauthEndpoints.any { path.contains(it, ignoreCase = true) }

        // If request sets X-Skip-Auth header, skip adding Authorization regardless of path
        val hasSkipHeader = original.header("X-Skip-Auth") != null

        val token = tokenStore.getAccessToken()
        val tokenPresent = !token.isNullOrBlank()

        val newReq = if (!isUnauthEndpoint && tokenPresent && !hasSkipHeader) {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            original
        }


        return chain.proceed(newReq)
    }
}
