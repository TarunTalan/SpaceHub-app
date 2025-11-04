package com.example.myapplication.data.community.model

data class RejectRequest(
    val communityName: String,
    val creatorEmail: String,
    val userEmail: String
)