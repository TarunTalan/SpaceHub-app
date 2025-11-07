package com.example.myapplication.data.friends.model

// Model for a single incoming friend request item as returned by the API.
// Matches API: { id, firstName, lastName, email }
// Fields are nullable to handle partial responses safely.
data class IncomingFriendRequestItem(
    val id: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null
)
