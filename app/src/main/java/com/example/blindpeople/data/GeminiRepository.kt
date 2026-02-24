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

private const val TAG_GEMINI = "BlindPeopleLog"

class GeminiRepository(
    private val apiKeyProvider: ApiKeyProvider,
    private val client: OkHttpClient = defaultOkHttpClient(),
    private val moshi: Moshi = Moshi.Builder().build(),
) {
    private val requestAdapter = moshi.adapter(GeminiRequest::class.java)
    private val responseAdapter = moshi.adapter(GeminiResponse::class.java)
    private val visionResultAdapter = moshi.adapter(VisionResult::class.java)

    @Volatile
    private var inFlightJob: Job? = null

    suspend fun analyzeImageJpegBase64(
        jpegBase64: String,
    ): Result<VisionResult> = withContext(Dispatchers.IO) {
        Log.d(TAG_GEMINI, "[GeminiRepository.analyzeImageJpegBase64] start, jpegBase64.length=${jpegBase64.length}")
        // Cancel any stale request.
        inFlightJob?.cancel()
        inFlightJob = coroutineContext[Job]

        val apiKey = apiKeyProvider.getApiKey().ifBlank { BuildConfig.GEMINI_API_KEY }.trim()
        if (apiKey.isBlank()) {
            Log.e(TAG_GEMINI, "[GeminiRepository.analyzeImageJpegBase64] Missing Gemini API key")
            return@withContext Result.failure(IllegalStateException("Missing Gemini API key"))
        }

        val requestPayload = buildRequestPayload(jpegBase64)
        val json = requestAdapter.toJson(requestPayload).toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(json)
            .build()

        try {
            val startTime = System.currentTimeMillis()
            client.newCall(request).execute().use { resp ->
                val elapsedMs = System.currentTimeMillis() - startTime
                Log.d("Time-Taken", "[GeminiRepository] API response received in ${elapsedMs}ms (HTTP ${resp.code})")
                coroutineContext.ensureActive()
                if (!resp.isSuccessful) {
                    val errorBody = resp.body?.string().orEmpty()
                    Log.e(TAG_GEMINI, "[GeminiRepository.analyzeImageJpegBase64] Gemini API failed body=$errorBody")
                    return@withContext Result.failure(
                        RuntimeException("Gemini API failed: HTTP ${resp.code} - $errorBody")
                    )
                }
                val body = resp.body?.string().orEmpty()
                Log.d(TAG_GEMINI, "[GeminiRepository.analyzeImageJpegBase64] raw body length=${body.length}")
                
                val parsed = responseAdapter.fromJson(body)
                    ?: return@withContext Result.failure(RuntimeException("Empty Gemini response"))

                val assistantText = parsed.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: return@withContext Result.failure(RuntimeException("No assistant message in response"))
                Log.d(TAG_GEMINI, "[GeminiRepository.analyzeImageJpegBase64] assistantText=${assistantText.take(500)}")

                // The model was instructed to output strict JSON (VisionResult).
                // Strip markdown code fences if present via JSON response mime type it shouldn't, but just in case
                val cleanJson = assistantText
                    .replace("```json", "")
                    .replace("```", "")
                    .trim()

                val vision = visionResultAdapter.fromJson(cleanJson)
                    ?: return@withContext Result.failure(RuntimeException("Failed to parse vision JSON"))
                Log.d(TAG_GEMINI, "[GeminiRepository.analyzeImageJpegBase64] vision objects=${vision.objects.size} textCount=${vision.text.size}")

                return@withContext Result.success(vision)
            }
        } catch (ce: CancellationException) {
            Log.d(TAG_GEMINI, "[GeminiRepository.analyzeImageJpegBase64] cancelled")
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG_GEMINI, "[GeminiRepository.analyzeImageJpegBase64] failure", t)
            return@withContext Result.failure(t)
        }
    }

    private fun buildRequestPayload(jpegBase64: String): GeminiRequest {
        val safeB64 = jpegBase64.replace("\n", "")
        return GeminiRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(
                        GeminiPart(
                            text = "You are assisting a visually impaired user. Identify physical objects and visible text in the image. Estimate approximate distance in meters for each object. Respond ONLY with valid JSON in this exact format: {\"objects\":[{\"name\":\"object name\",\"estimated_distance_m\":5.0}], \"text\":[\"text1\",\"text2\"]}. Do not include any other text or markdown formatting."
                        ),
                        GeminiPart(
                            inlineData = GeminiInlineData(
                                mimeType = "image/jpeg",
                                data = safeB64
                            )
                        )
                    )
                )
            ),
            generationConfig = GeminiGenerationConfig(
                responseMimeType = "application/json"
            )
        )
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
