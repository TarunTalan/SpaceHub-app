package com.example.myapplication.data.community.model

data class GetMyCommunitiesResponse(
    val `data`: DataCommunityList,
    val message: String,
    val status: Int
)