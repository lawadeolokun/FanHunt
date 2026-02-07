package com.example.fanhunt.model

data class QrCodes(
    val points: Int = 50,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val active: Boolean = true,
    val radius: Int = 50,

    )
