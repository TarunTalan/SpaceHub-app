package com.example.myapplication.data.community.model

data class RemoveMemberRequest(
    val communityId: String,
    val requesterEmail: String,
    val userEmail: String
)