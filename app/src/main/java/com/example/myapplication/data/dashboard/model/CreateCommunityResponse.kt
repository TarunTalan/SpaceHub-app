package com.example.myapplication.data.dashboard.model

data class CreateCommunityResponse(
    val status: Int,
    val message: String,
    val data: CommunityData?
)
{
    data class CommunityData(
        val imageUrl: String?,
        val name: String,
        val communityId: String,
    )
}
