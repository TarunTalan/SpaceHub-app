package com.example.myapplication.data.community.model

data class GetAllChatRoomsResponse(
    val `data`: List<DataChatRoom>,
    val message: String,
    val status: Int
)