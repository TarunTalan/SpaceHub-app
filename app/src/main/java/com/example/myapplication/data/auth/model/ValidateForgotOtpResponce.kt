package com.example.myapplication.data.auth.model
import com.google.gson.annotations.SerializedName

data class ValidateForgotOtpResponce(
    val status: Int,
    val message: String?,
    @SerializedName("data") val data: Data?
)
