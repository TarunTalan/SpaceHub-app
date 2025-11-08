package com.example.myapplication.data.groups.model

data class DataXXXXXX(
    val chatRoom: ChatRoom,
    val createdAt: String,
    val createdBy: CreatedBy,
    val description: String,
    val id: String,
    val imageUrl: String,
    val inviteCode: Any,
    val members: List<Member>,
    val name: String,
    val updatedAt: String,
    val voiceRoom: Any
)