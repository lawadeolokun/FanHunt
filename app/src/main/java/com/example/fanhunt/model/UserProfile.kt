package com.example.fanhunt.model

data class UserProfile(
    val displayName: String = "",
    val email: String = "",
    val favouriteTeam: String = "",
    val totalPoints: Long = 0
)
