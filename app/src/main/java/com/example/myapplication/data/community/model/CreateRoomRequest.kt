package com.example.myapplication.data.community.model

data class CreateRoomRequest(
    val requesterEmail: String,
    val roomName: String
)