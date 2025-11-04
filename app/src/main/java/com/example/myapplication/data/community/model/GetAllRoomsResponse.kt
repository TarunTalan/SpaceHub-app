package com.example.myapplication.data.community.model

import com.google.gson.JsonElement

data class GetAllRoomsResponse(
    val status: Int? = null,
    val message: String? = null,
    val data: JsonElement? = null
)
