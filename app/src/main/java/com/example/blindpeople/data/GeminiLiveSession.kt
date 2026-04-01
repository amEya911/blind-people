package com.example.blindpeople.data

import android.util.Log
import com.example.blindpeople.BuildConfig
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages a persistent WebSocket to the Gemini Live API (BidiGenerateContent).
 *
 * Architecture:
 *  - Input:  Camera frames (JPEG, Base64) streamed via [sendFrame].
 *  - Output: Raw PCM audio chunks (Base64) emitted on [audioChunks].
 *
 * The model generates a natural voice description of the scene and streams
 * audio bytes back. We play them directly through NativeAudioPlayer.
 */
class GeminiLiveSession(
    private val apiKeyProvider: ApiKeyProvider,
    private val moshi: Moshi = Moshi.Builder().build(),
) {
    companion object {
        private const val TAG = "BlindPeopleLog"

        private const val WS_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        private const val MODEL = "models/gemini-2.5-flash-native-audio-latest"

        private const val SYSTEM_PROMPT =
            "You are a vision assistant for a blind person. " +
            "Your job is to identify the single most prominent object in the current camera frame. " +
            "Always look closely at the newest image, do not guess or rely on past memory. " +
            "Format your response as a very short sentence, like: 'Nearest is a [Object Name]'. " +
            "Keep it brief."

        private const val FOLLOW_UP_PROMPT = "Look at the latest camera frame. What is the nearest object right now?"

        private const val MAX_RECONNECT_DELAY_MS = 16_000L
    }

    /** Base64-encoded PCM audio chunks from the model's voice response. */
    private val _audioChunks = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val audioChunks: SharedFlow<String> = _audioChunks

    /** Emitted when the model finishes a turn (useful for UI status). */
    private val _turnComplete = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val turnComplete: SharedFlow<Unit> = _turnComplete

    private val connected = AtomicBoolean(false)
    private val setupComplete = AtomicBoolean(false)
    private val wsRef = AtomicReference<WebSocket?>(null)

    private var reconnectDelayMs = 1_000L
    @Volatile private var shouldBeConnected = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    // ─── Public API ──────────────────────────────────────────────────

    fun connect() {
        if (connected.get()) {
            Log.d(TAG, "[GeminiLiveSession.connect] already connected, skipping")
            return
        }
        shouldBeConnected = true

        val apiKey = apiKeyProvider.getApiKey().ifBlank { BuildConfig.GEMINI_API_KEY }.trim()
        if (apiKey.isBlank()) {
            Log.e(TAG, "[GeminiLiveSession.connect] Missing Gemini API key")
            return
        }

        val url = "$WS_BASE_URL?key=$apiKey"
        Log.d(TAG, "[GeminiLiveSession.connect] opening WebSocket")

        val request = Request.Builder().url(url).build()
        client.newWebSocket(request, Listener())
    }

    fun disconnect() {
        shouldBeConnected = false
        reconnectDelayMs = 1_000L
        val ws = wsRef.getAndSet(null)
        if (ws != null) {
            Log.d(TAG, "[GeminiLiveSession.disconnect] closing WebSocket")
            ws.close(1000, "User stopped")
        }
        connected.set(false)
        setupComplete.set(false)
    }

    /**
     * Send a camera frame. Fire-and-forget — the model will respond
     * asynchronously with audio chunks.
     */
    fun sendFrame(jpegBase64: String) {
        val ws = wsRef.get()
        if (ws == null || !connected.get() || !setupComplete.get()) {
            Log.d(TAG, "[GeminiLiveSession.sendFrame] dropped: ws=${ws != null} connected=${connected.get()} setup=${setupComplete.get()}")
            return
        }

        val safeB64 = jpegBase64.replace("\n", "")
        val message = """
            {
              "realtimeInput": {
                "mediaChunks": [
                  {
                    "mimeType": "image/jpeg",
                    "data": "$safeB64"
                  }
                ]
              }
            }
        """.trimIndent()

        val sent = ws.send(message)
        if (sent) {
            Log.d(TAG, "[GeminiLiveSession.sendFrame] sent frame, b64len=${jpegBase64.length}")
        } else {
            Log.w(TAG, "[GeminiLiveSession.sendFrame] send returned false")
        }
    }

    fun isConnected(): Boolean = connected.get() && setupComplete.get()

    // ─── WebSocket Listener ──────────────────────────────────────────

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "[GeminiLiveSession.onOpen] WebSocket opened, sending setup")
            wsRef.set(webSocket)
            connected.set(true)
            reconnectDelayMs = 1_000L

            val promptJson = moshi.adapter(String::class.java).toJson(SYSTEM_PROMPT)
            val setupMessage = """
                {
                  "setup": {
                    "model": "$MODEL",
                    "systemInstruction": {
                      "parts": [{"text": $promptJson}]
                    },
                    "generationConfig": {
                      "responseModalities": ["AUDIO"],
                      "speechConfig": {
                        "voiceConfig": {
                          "prebuiltVoiceConfig": {
                            "voiceName": "Aoede"
                          }
                        }
                      }
                    }
                  }
                }
            """.trimIndent()

            val sent = webSocket.send(setupMessage)
            Log.d(TAG, "[GeminiLiveSession.onOpen] setup sent=$sent")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                parseServerMessage(text)
            } catch (e: Exception) {
                Log.e(TAG, "[GeminiLiveSession.onMessage] parse error", e)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            try {
                val text = bytes.utf8()
                parseServerMessage(text)
            } catch (e: Exception) {
                Log.e(TAG, "[GeminiLiveSession.onMessage/binary] parse error", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "[GeminiLiveSession.onClosing] code=$code reason=$reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "[GeminiLiveSession.onClosed] code=$code reason=$reason")
            wsRef.set(null)
            connected.set(false)
            setupComplete.set(false)
            maybeReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "[GeminiLiveSession.onFailure] error=${t.message}", t)
            wsRef.set(null)
            connected.set(false)
            setupComplete.set(false)
            maybeReconnect()
        }
    }

    // ─── Message Parsing ─────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun parseServerMessage(raw: String) {
        val mapAdapter = moshi.adapter(Map::class.java)
        val message = mapAdapter.fromJson(raw) as? Map<String, Any?> ?: return

        // setupComplete → unlock frame sending + send initial prompt
        if (message.containsKey("setupComplete")) {
            Log.d(TAG, "[GeminiLiveSession] setupComplete received, sending initial prompt")
            setupComplete.set(true)

            // The model needs an explicit user turn to start responding.
            // Without this, it receives images but stays silent.
            val ws = wsRef.get()
            if (ws != null) {
                val kickstart = """
                    {
                      "clientContent": {
                        "turns": [
                          {
                            "role": "user",
                            "parts": [{"text": "Look at the camera. What is the nearest object?"}]
                          }
                        ],
                        "turnComplete": true
                      }
                    }
                """.trimIndent()
                val sent = ws.send(kickstart)
                Log.d(TAG, "[GeminiLiveSession] initial prompt sent=$sent")
            }
            return
        }

        val serverContent = message["serverContent"] as? Map<String, Any?> ?: return

        // Extract audio chunks from modelTurn.parts
        val modelTurn = serverContent["modelTurn"] as? Map<String, Any?>
        if (modelTurn != null) {
            val parts = modelTurn["parts"] as? List<Map<String, Any?>>
            parts?.forEach { part ->
                val inlineData = part["inlineData"] as? Map<String, Any?>
                if (inlineData != null) {
                    val data = inlineData["data"] as? String
                    if (data != null) {
                        _audioChunks.tryEmit(data)
                    }
                }
            }
        }

        // turnComplete → re-trigger the model so it keeps responding
        val isTurnComplete = serverContent["turnComplete"] as? Boolean ?: false
        if (isTurnComplete) {
            Log.d(TAG, "[GeminiLiveSession] turnComplete, sending follow-up prompt")
            _turnComplete.tryEmit(Unit)

            // Send a follow-up prompt to keep the loop going
            val ws = wsRef.get()
            if (ws != null && shouldBeConnected) {
                val followUp = """
                    {
                      "clientContent": {
                        "turns": [
                          {
                            "role": "user",
                            "parts": [{"text": "$FOLLOW_UP_PROMPT"}]
                          }
                        ],
                        "turnComplete": true
                      }
                    }
                """.trimIndent()
                ws.send(followUp)
            }
        }
    }

    // ─── Reconnection ────────────────────────────────────────────────

    private fun maybeReconnect() {
        if (!shouldBeConnected) {
            Log.d(TAG, "[GeminiLiveSession.maybeReconnect] shouldBeConnected=false, not reconnecting")
            return
        }
        Log.d(TAG, "[GeminiLiveSession.maybeReconnect] reconnecting in ${reconnectDelayMs}ms")
        Thread {
            try {
                Thread.sleep(reconnectDelayMs)
                reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                if (shouldBeConnected) {
                    connect()
                }
            } catch (_: InterruptedException) {}
        }.start()
    }
}
