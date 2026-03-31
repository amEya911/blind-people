package com.example.blindpeople.data

import android.util.Log
import com.example.blindpeople.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Translates object labels from English to Hindi/Marathi using the Gemini API.
 * Results are cached per-language so each label is only translated once per session.
 * Falls back to English if translation fails.
 */
class LabelTranslator(
    private val apiKeyProvider: ApiKeyProvider,
    private val client: OkHttpClient,
) {
    companion object {
        private const val TAG = "BlindPeopleLog"
        private const val GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-lite:generateContent"
    }

    // Cache: langCode -> (englishLabel -> translatedLabel)
    private val cache = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    /**
     * Translate a list of English labels to the target language.
     * Uses cache first, falls back to Gemini API for uncached labels.
     * Returns a map of English -> Translated.
     */
    suspend fun translate(
        labels: List<String>,
        targetLangCode: String,
    ): Map<String, String> {
        if (targetLangCode == "en") return labels.associateWith { it }

        val langName = when (targetLangCode) {
            "hi" -> "Hindi"
            "mr" -> "Marathi"
            else -> return labels.associateWith { it }
        }

        val langCache = cache.getOrPut(targetLangCode) { ConcurrentHashMap() }
        val uniqueLabels = labels.distinct()
        val uncached = uniqueLabels.filter { !langCache.containsKey(it) }

        if (uncached.isNotEmpty()) {
            Log.d(TAG, "[LabelTranslator] translating ${uncached.size} labels to $langName: $uncached")
            val translations = callGemini(uncached, langName)
            langCache.putAll(translations)
            Log.d(TAG, "[LabelTranslator] cached ${translations.size} translations")
        }

        return labels.associateWith { langCache[it] ?: it }
    }

    /**
     * Batch-translate labels via Gemini API.
     * Returns a map of English -> Translated for successfully translated labels.
     */
    private suspend fun callGemini(
        labels: List<String>,
        langName: String,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider.getApiKey().ifBlank { BuildConfig.GEMINI_API_KEY }.trim()
        if (apiKey.isBlank()) {
            Log.e(TAG, "[LabelTranslator] No API key available")
            return@withContext emptyMap()
        }

        val labelsStr = labels.joinToString(", ")
        val prompt = "Translate these English object names to $langName. " +
                "Return ONLY a JSON object mapping each English word to its $langName translation. " +
                "Do not add any explanation. Example: {\"person\":\"व्यक्ति\"}. " +
                "Translate: $labelsStr"

        val requestJson = """
            {
                "contents": [{"parts": [{"text": ${JSONObject.quote(prompt)}}]}],
                "generationConfig": {"responseMimeType": "application/json"}
            }
        """.trimIndent()

        val httpRequest = Request.Builder()
            .url("$GEMINI_URL?key=$apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(httpRequest).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "[LabelTranslator] API failed: HTTP ${resp.code}")
                    return@withContext emptyMap()
                }

                val body = resp.body?.string().orEmpty()
                val responseJson = JSONObject(body)
                val candidateText = responseJson
                    .optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text", "")
                    ?: ""

                val cleanJson = candidateText
                    .replace("```json", "")
                    .replace("```", "")
                    .trim()

                if (cleanJson.isBlank()) return@withContext emptyMap()

                val translationsJson = JSONObject(cleanJson)
                val result = mutableMapOf<String, String>()
                for (key in translationsJson.keys()) {
                    result[key] = translationsJson.getString(key)
                }
                Log.d(TAG, "[LabelTranslator] translated: $result")
                return@withContext result
            }
        } catch (e: Exception) {
            Log.e(TAG, "[LabelTranslator] translation failed", e)
            return@withContext emptyMap()
        }
    }
}
