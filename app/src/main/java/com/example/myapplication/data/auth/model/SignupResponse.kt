package com.example.myapplication.data.auth.model

import com.google.gson.annotations.SerializedName

data class SignupResponse(
    @SerializedName("status") val status: Int,
    @SerializedName("message") val message: String?,
    @SerializedName("data") val data: String?
)