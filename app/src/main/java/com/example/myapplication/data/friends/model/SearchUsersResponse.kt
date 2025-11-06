package com.example.myapplication.data.friends.model

data class SearchUsersResponse(
    val `data`: DataSearchUser,
    val message: String,
    val status: Int
)