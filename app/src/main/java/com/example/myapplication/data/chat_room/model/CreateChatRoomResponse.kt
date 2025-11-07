package com.example.myapplication.data.chat_room.model

data class CreateChatRoomResponse(
    val `data`: DataChatRoom,
    val message: String,
    val status: Int
)