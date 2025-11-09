package com.example.myapplication.data.friends.model

data class Content(
    val avatarUrl: String? = null,
    val avatarPreviewUrl: String? = null,
    val avatarKey: String? = null,
    val email: String = "",
    val userId: String = "",
    val username: String = "",
    val firstName: String? = null,
    val lastName: String? = null
)