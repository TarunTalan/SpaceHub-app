package com.example.myapplication.data.groups.model

data class GetAllMembersResponse(
    val `data`: List<DataXXX>,
    val message: String,
    val status: Int
)