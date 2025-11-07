package com.example.myapplication.data.dashboard.model

// Minimal response model for create local group API
// Matches common pattern: { status: Int, message: String, data: { id: Int, name: String, imageUrl: String? } }
data class CreateLocalGroupResponse(
    val status: Int,
    val message: String,
    val data: LocalGroupData?
)

{
    data class LocalGroupData(
        val id: Int?,
        val name: String?,
        val imageUrl: String?
    )
}

