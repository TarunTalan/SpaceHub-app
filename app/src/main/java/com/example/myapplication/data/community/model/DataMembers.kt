package com.example.myapplication.data.community.model

data class DataMembers(
    val communityId: String,
    val communityName: String,
    val members: List<Member>,
    val totalMembers: Int
)