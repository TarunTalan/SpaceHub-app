package com.example.myapplication.data.groups.model

data class DeleteLocalGroupRequest(
    val groupId: String,
    val requesterEmail: String
)