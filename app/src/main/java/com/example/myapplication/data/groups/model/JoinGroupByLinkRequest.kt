package com.example.myapplication.data.groups.model

data class JoinGroupByLinkRequest(
    val acceptorEmail: String,
    val groupId: String,
    val inviteCode: String
)