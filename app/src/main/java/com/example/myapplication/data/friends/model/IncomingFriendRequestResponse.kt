package com.example.myapplication.data.friends.model


data class IncomingFriendRequestResponse(
    val `data`: List<IncomingFriendRequestItem>,
    val message: String,
    val status: Int
)