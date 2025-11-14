package com.example.myapplication.data.voice.model

data class GetAllVoiceRoomsResponse(
    val count: Int,
    val voiceRooms: List<VoiceRoomX>
)