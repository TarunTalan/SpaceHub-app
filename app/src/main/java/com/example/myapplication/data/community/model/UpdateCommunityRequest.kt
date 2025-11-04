package com.example.myapplication.data.community.model

data class UpdateCommunityRequest(
    val communityId: String,
    val description: String,
    val name: String
)