package com.example.fanhunt.model

data class PhotoPost(
    var id: String = "",
    var userId: String = "",
    var imageUrl: String = "",
    var caption: String = "",
    var votes: Int = 0,
    var timestamp: Long = 0,   // 🔥 MUST MATCH FIRESTORE
    var votedBy: MutableList<String> = mutableListOf()
)