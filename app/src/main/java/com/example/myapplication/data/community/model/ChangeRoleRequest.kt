package com.example.myapplication.data.community.model

data class ChangeRoleRequest(
    val communityId: String,
    val newRole: String,
    val requesterEmail: String,
    val targetUserEmail: String
)