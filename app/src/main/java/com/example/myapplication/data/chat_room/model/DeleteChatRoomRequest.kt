package com.example.myapplication.data.chat_room.model

data class DeleteChatRoomRequest(
    val roomCode: String,
    val userId: String
)