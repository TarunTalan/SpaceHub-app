package com.example.myapplication.data.community.model

data class CreateRoomResponse(
    val `data`: DataRoom,
    val message: String,
    val status: Int
)