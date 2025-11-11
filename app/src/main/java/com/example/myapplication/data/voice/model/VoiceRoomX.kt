package com.example.myapplication.data.voice.model

data class VoiceRoomX(
    val active: Boolean,
    val createdAt: Any,
    val createdBy: String,
    val id: Int,
    val janusRoomId: Int,
    val name: String,
    val roomCode: String
)