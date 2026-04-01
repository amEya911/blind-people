package com.example.blindpeople.data

import android.util.Log
import com.example.blindpeople.BuildConfig
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiUnaryClient(
    private val apiKeyProvider: ApiKeyProvider,
    private val moshi: Moshi = Moshi.Builder().build(),
) {
    companion object {
        private const val TAG = "BlindPeopleLog"
        private const val MODEL = "models/gemini-2.5-flash-lite"
        private const val API_URL = "https://generativelanguage.googleapis.com/v1beta/$MODEL:generateContent"

        private const val SYSTEM_PROMPT = 
            "You are a vision assistant for a blind person. " +
            "Identify the single most prominent object in the camera frame. " +
            "Respond in 5 words or less. " +
            "Example: 'Nearest is a door'."
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun detectObject(jpegBase64: String): String? = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider.getApiKey().ifBlank { BuildConfig.GEMINI_API_KEY }.trim()
        if (apiKey.isBlank()) {
            Log.e(TAG, "[GeminiUnaryClient] Missing Gemini API key")
            return@withContext null
        }

        val url = "$API_URL?key=$apiKey"
        
        // Remove newlines just in case base64 encoding added them
        val safeB64 = jpegBase64.replace("\n", "")

        val jsonBody = """
            {
              "systemInstruction": {
                "parts": [{"text": "${SYSTEM_PROMPT.replace("\"", "\\\"")}"}]
              },
              "contents": [
                {
                  "role": "user",
                  "parts": [
                    {
                      "inlineData": {
                        "mimeType": "image/jpeg",
                        "data": "$safeB64"
                      }
                    },
                    {
                      "text": "Look at the camera frame. What is the nearest object?"
                    }
                  ]
                }
              ],
              "generationConfig": {
                "temperature": 0.2,
                "maxOutputTokens": 15
              }
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.e(TAG, "[GeminiUnaryClient] HTTP Error ${response.code}: $errorBody")
                    return@withContext null
                }

                val bodyString = response.body?.string() ?: return@withContext null
                return@withContext extractText(bodyString)
            }
        } catch (e: IOException) {
            Log.e(TAG, "[GeminiUnaryClient] Network error: ${e.message}", e)
            return@withContext null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractText(jsonResponse: String): String? {
        try {
            val mapAdapter = moshi.adapter(Map::class.java)
            val map = mapAdapter.fromJson(jsonResponse) as? Map<String, Any?> ?: return null
            
            val candidates = map["candidates"] as? List<Map<String, Any?>> ?: return null
            if (candidates.isEmpty()) return null
            
            val content = candidates[0]["content"] as? Map<String, Any?> ?: return null
            val parts = content["parts"] as? List<Map<String, Any?>> ?: return null
            if (parts.isEmpty()) return null
            
            return parts[0]["text"] as? String
        } catch (e: Exception) {
            Log.e(TAG, "[GeminiUnaryClient] Failed to parse generated text", e)
            return null
        }
    }
}
