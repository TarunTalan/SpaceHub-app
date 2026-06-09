package com.example.myapplication.data.file_sharing.model

data class PresignedUrlResponse(
    val `data`: String,
    val message: String,
    val status: Int
)