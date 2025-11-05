package com.example.myapplication.data.search.model

data class Community(
    val communityId: String,
    val description: String,
    val imageKey: String,
    val imageUrl: String,
    val isMember: Boolean,
    val isRequested: Boolean,
    val name: String
)