package com.example.myapplication.data.chat_room.model

data class GetChatRoomSummaryResponse(
    val `data`: List<Data>,
    val message: String,
    val status: Int
)