package com.example.myapplication.data.auth.model

import com.google.gson.annotations.SerializedName
data class LoginResponse(
    @SerializedName("status") val status: Int,
    @SerializedName("message") val message: String?,
    @SerializedName("data") val data: TokenData?
) {
    data class TokenData(
        @SerializedName("accessToken") val accessToken: String?,
        @SerializedName("refreshToken") val refreshToken: String?
    )
}
