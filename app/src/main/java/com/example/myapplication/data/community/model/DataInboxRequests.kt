package com.example.myapplication.data.community.model

data class DataInboxRequests(
    val communityId: String,
    val communityName: String,
    val requests: List<Request>
)