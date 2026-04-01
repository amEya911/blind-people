package com.example.blindpeople.data

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class VisionResult(
    val objects: List<DetectedObject> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class DetectedObject(
    val name: String,
    val estimated_distance_m: Double,
    val position: String = "center", // "left", "center", "right"
)

/**
 * Optional secure storage; you can set the key at runtime if you don't want to use BuildConfig.
 */
interface ApiKeyProvider {
    fun getApiKey(): String
}
