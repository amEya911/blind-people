package com.example.blindpeople.data

import android.util.Log
import com.example.blindpeople.BuildConfig
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

private const val TAG_OPENAI = "BlindPeopleLog"

class OpenAiRepository(
    private val apiKeyProvider: ApiKeyProvider,
    private val client: OkHttpClient = defaultOkHttpClient(),
    private val moshi: Moshi = Moshi.Builder().build(),
) {
    private val chatResponseAdapter = moshi.adapter(ChatCompletionResponse::class.java)
    private val visionResultAdapter = moshi.adapter(VisionResult::class.java)

    @Volatile
    private var inFlightJob: Job? = null

    suspend fun analyzeImageJpegBase64(
        jpegBase64: String,
    ): Result<VisionResult> = withContext(Dispatchers.IO) {
        Log.d(TAG_OPENAI, "[OpenAiRepository.analyzeImageJpegBase64] start, jpegBase64.length=${jpegBase64.length}")
        // Cancel any stale request.
        inFlightJob?.cancel()
        inFlightJob = coroutineContext[Job]

        val apiKey = apiKeyProvider.getApiKey().ifBlank { BuildConfig.OPENAI_API_KEY }.trim()
        if (apiKey.isBlank()) {
            Log.e(TAG_OPENAI, "[OpenAiRepository.analyzeImageJpegBase64] Missing OpenAI API key")
            return@withContext Result.failure(IllegalStateException("Missing OpenAI API key"))
        }

        val payload = buildChatPayload(jpegBase64)
        val json = payload.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(json)
            .build()

        try {
            client.newCall(request).execute().use { resp ->
                coroutineContext.ensureActive()
                Log.d(TAG_OPENAI, "[OpenAiRepository.analyzeImageJpegBase64] HTTP ${resp.code}")
                if (!resp.isSuccessful) {
                    val errorBody = resp.body?.string().orEmpty()
                    Log.e(TAG_OPENAI, "[OpenAiRepository.analyzeImageJpegBase64] OpenAI API failed body=$errorBody")
                    return@withContext Result.failure(
                        RuntimeException("OpenAI API failed: HTTP ${resp.code} - $errorBody")
                    )
                }
                val body = resp.body?.string().orEmpty()
                Log.d(TAG_OPENAI, "[OpenAiRepository.analyzeImageJpegBase64] raw body length=${body.length}")
                val parsed = chatResponseAdapter.fromJson(body)
                    ?: return@withContext Result.failure(RuntimeException("Empty OpenAI response"))

                val assistantText = extractAssistantText(parsed)
                    ?: return@withContext Result.failure(RuntimeException("No assistant message in response"))
                Log.d(TAG_OPENAI, "[OpenAiRepository.analyzeImageJpegBase64] assistantText=${assistantText.take(500)}")

                // The model was instructed to output strict JSON (VisionResult).
                // Strip markdown code fences if present
                val cleanJson = assistantText
                    .replace("```json", "")
                    .replace("```", "")
                    .trim()

                val vision = visionResultAdapter.fromJson(cleanJson)
                    ?: return@withContext Result.failure(RuntimeException("Failed to parse vision JSON"))
                Log.d(TAG_OPENAI, "[OpenAiRepository.analyzeImageJpegBase64] vision objects=${vision.objects.size} textCount=${vision.text.size}")

                return@withContext Result.success(vision)
            }
        } catch (ce: CancellationException) {
            Log.d(TAG_OPENAI, "[OpenAiRepository.analyzeImageJpegBase64] cancelled")
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG_OPENAI, "[OpenAiRepository.analyzeImageJpegBase64] failure", t)
            return@withContext Result.failure(t)
        }
    }

    private fun extractAssistantText(resp: ChatCompletionResponse): String? {
        return resp.choices?.firstOrNull()?.message?.content?.trim()
    }

    private fun buildChatPayload(jpegBase64: String): String {
        // Use OpenAI Chat Completions API format
        val safeB64 = jpegBase64.replace("\n", "")
        return """
          {
            "model": "gpt-4o-mini",
            "messages": [
              {
                "role": "user",
                "content": [
                  {
                    "type": "text",
                    "text": "You are assisting a visually impaired user. Identify physical objects and visible text in the image. Estimate approximate distance in meters for each object. Respond ONLY with valid JSON in this exact format: {\"objects\":[{\"name\":\"object name\",\"estimated_distance_m\":5.0}], \"text\":[\"text1\",\"text2\"]}. Do not include any other text or markdown formatting."
                  },
                  {
                    "type": "image_url",
                    "image_url": {
                      "url": "data:image/jpeg;base64,${escapeJson(safeB64)}"
                    }
                  }
                ]
              }
            ],
            "max_tokens": 500
          }
        """.trimIndent()
    }

    private fun escapeJson(s: String): String {
        // Base64 is mostly safe, but escape just in case.
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    companion object {
        fun defaultOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}

/**
 * Optional secure storage; you can set the key at runtime if you don't want to use BuildConfig.
 */
interface ApiKeyProvider {
    fun getApiKey(): String
}