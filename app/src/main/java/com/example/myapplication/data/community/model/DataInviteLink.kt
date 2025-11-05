package com.example.myapplication.data.community.model

data class DataInviteLink(
    val communityId: String,
    val email: Any,
    val expiresAt: String,
    val inviteCode: String,
    val inviteLink: String,
    val inviterEmail: String,
    val maxUses: Int,
    val status: String,
    val uses: Int
)