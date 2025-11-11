package com.example.myapplication.data.groups.model

data class DataXX(
    val chatRoomCode: String,
    val createdAt: String,
    val createdByEmail: String,
    val description: String,
    val id: String,
    val imageUrl: Any,
    val memberEmails: List<String>,
    val name: String,
    val totalMembers: Int,
    val updatedAt: String,
    val imageKey: String,
)