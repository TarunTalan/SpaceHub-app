package com.example.myapplication.data.community.model

data class GetAllRoomsResponse(
    val `data`: List<DataRoom>,
    val message: String,
    val status: Int
)