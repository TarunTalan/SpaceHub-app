package com.example.myapplication.data.friends.model

data class RespondFriendRequest(
    val accept: String,
    val requesterEmail: String,
    val userEmail: String
)