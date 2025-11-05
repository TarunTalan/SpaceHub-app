package com.example.myapplication.data.community.model

data class UploadBannerResponse(
    val `data`: Data,
    val message: String,
    val status: Int
)
data class DataUploadBanner(
    val createdAt: String,
    val createdBy: String
)