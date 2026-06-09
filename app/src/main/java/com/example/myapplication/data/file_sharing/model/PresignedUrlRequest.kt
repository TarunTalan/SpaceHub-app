package com.example.myapplication.data.file_sharing.model

data class PresignedUrlRequest(
    val contentType: String,
    val `file`: String
)