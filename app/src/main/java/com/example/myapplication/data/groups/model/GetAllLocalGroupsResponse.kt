package com.example.myapplication.data.groups.model

data class GetAllLocalGroupsResponse(
    val `data`: List<DataX>,
    val message: String,
    val status: Int
)