package com.example.myapplication.data.auth.model

import com.google.gson.annotations.SerializedName

// Use the shared Data model for tokens (keeps models consistent across responses)
data class LoginResponse(
    @SerializedName("status") val status: Int,
    @SerializedName("message") val message: String?,
    @SerializedName("data") val data: Data?
)
