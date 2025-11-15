package com.example.myapplication.data.auth.model

import com.google.gson.annotations.SerializedName

data class Data(
    @SerializedName("accessToken") val accessToken: String?,
    @SerializedName("refreshToken") val refreshToken: String?,
    @SerializedName("email") val email: String?
)