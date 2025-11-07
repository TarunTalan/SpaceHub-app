package com.example.myapplication.data.chat_room.model

data class GetAllChatRoomsResponse(
    val `data`: List<DataChatRoom>,
    val message: String,
    val status: Int
)