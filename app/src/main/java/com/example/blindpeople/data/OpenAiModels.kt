package com.example.blindpeople.data

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class VisionResult(
    val objects: List<DetectedObject> = emptyList(),
    val text: List<String> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class DetectedObject(
    val name: String,
    val estimated_distance_m: Double,
)

/**
 * OpenAI Chat Completion API response structure
 */
@JsonClass(generateAdapter = true)
data class ChatCompletionResponse(
    val id: String? = null,
    val choices: List<ChatChoice>? = null,
)

@JsonClass(generateAdapter = true)
data class ChatChoice(
    val message: ChatMessage? = null,
    val index: Int? = null,
)

@JsonClass(generateAdapter = true)
data class ChatMessage(
    val role: String? = null,
    val content: String? = null,
)