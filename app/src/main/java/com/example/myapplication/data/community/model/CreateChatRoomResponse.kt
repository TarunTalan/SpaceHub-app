package com.example.myapplication.data.community.model

data class CreateChatRoomResponse(
    val `data`: DataChatRoom,
    val message: String,
    val status: Int
)