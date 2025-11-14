package com.example.myapplication.data.voice.model

data class JoinVoiceRoomResponse(
    val handleId: String,
    val janusRoomId: Int,
    val message: String,
    val sessionId: String
)