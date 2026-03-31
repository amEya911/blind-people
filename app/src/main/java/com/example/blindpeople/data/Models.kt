package com.example.blindpeople.data

interface ApiKeyProvider {
    fun getApiKey(): String
}

data class DetectedObject(
    val name: String,
    val estimated_distance_m: Double,
    val position: String,
)
