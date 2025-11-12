package com.example.myapplication.data.file_sharing.model

data class UploadFileResponse(
    val `data`: Data,
    val message: String,
    val status: Int
)