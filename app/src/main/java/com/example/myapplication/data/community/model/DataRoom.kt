package com.example.myapplication.data.community.model

import com.example.myapplication.data.chat_room.model.DataChatRoom
// Extended DataRoom to include nested chat rooms (newChatRooms) and voiceRooms as returned by API
data class DataRoom(
    val id: String,
    val name: String,
    val roomCode: String,
    val newChatRooms: List<DataChatRoom>? = emptyList(),
    val voiceRooms: List<DataChatRoom>? = emptyList()
)