package com.example.myapplication.data.friends.model

data class FriendsListResponse(
    val `data`: List<Data>,
    val message: String,
    val status: Int
)