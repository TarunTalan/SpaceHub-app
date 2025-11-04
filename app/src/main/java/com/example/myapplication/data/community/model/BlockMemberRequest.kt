package com.example.myapplication.data.community.model

data class BlockMemberRequest(
    val block: Boolean,
    val communityId: String,
    val requesterEmail: String,
    val targetUserEmail: String
)