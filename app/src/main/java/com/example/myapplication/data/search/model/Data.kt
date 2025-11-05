package com.example.myapplication.data.search.model

data class Data(
    val communities: List<Community>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int
)