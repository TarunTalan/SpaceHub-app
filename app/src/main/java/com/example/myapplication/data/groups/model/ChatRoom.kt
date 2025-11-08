package com.example.myapplication.data.groups.model

data class ChatRoom(
    val id: String,
    val name: String,
    val newChatRooms: List<Any>,
    val roomCode: String,
    val voiceRooms: List<Any>
)