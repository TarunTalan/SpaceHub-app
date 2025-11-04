package com.example.myapplication.data.community.model

data class GetAllMembersRequest(
    val communityId: String,
    val requesterEmail: String
)