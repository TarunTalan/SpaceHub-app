package com.example.myapplication.data.community.model

data class RenameRoomRequest(
    val newRoomName: String,
    val requesterEmail: String
)