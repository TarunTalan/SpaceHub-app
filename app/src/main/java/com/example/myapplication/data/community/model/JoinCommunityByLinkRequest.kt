package com.example.myapplication.data.community.model

data class JoinCommunityByLinkRequest(
    val communityId: String,
    val inviteCode: String,
    val acceptorEmail: String
)
