package com.example.blindpeople.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class SpeechManager(
    context: Context,
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "BlindPeopleLog"
        private const val MAX_UTTERANCE_MS = 4_000L
    }

    private val tts = TextToSpeech(context.applicationContext, this)
    private val initialized = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)

    private var lastSpoken: String? = null
    private var lastSpokenAtMs: Long = 0L
    private var currentUtteranceId: String? = null
    private var currentUtteranceStartMs: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            Log.d(TAG, "[SpeechManager.onInit] SUCCESS")
            tts.language = Locale.US
            tts.setSpeechRate(1.0f)   // Normal speed — clear and understandable
            tts.setPitch(1.0f)        // Normal pitch
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    speaking.set(true)
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "[SpeechManager] onDone id=$utteranceId")
                    speaking.set(false)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "[SpeechManager] onError id=$utteranceId")
                    speaking.set(false)
                }
            })
            initialized.set(true)
            _ready.value = true
        } else {
            Log.e(TAG, "[SpeechManager.onInit] FAILURE status=$status")
            initialized.set(false)
            _ready.value = false
        }
    }

    /**
     * Hot-switch the TTS language without restarting the engine.
     * Returns true if the language is available on this device.
     */
    fun setLanguage(locale: Locale): Boolean {
        if (!initialized.get()) return false
        val result = tts.setLanguage(locale)
        val available = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
        Log.d(TAG, "[SpeechManager.setLanguage] locale=$locale available=$available")
        if (!available) {
            // Fall back to default if the language isn't installed
            Log.w(TAG, "[SpeechManager] Language $locale not available, falling back to US English")
            tts.language = Locale.US
        }
        return available
    }

    /**
     * Stop any current speech immediately.
     */
    fun stopSpeaking() {
        if (speaking.get()) {
            tts.stop()
            speaking.set(false)
        }
    }

    @Synchronized
    fun speakIfAllowed(
        text: String,
        audioEnabled: Boolean,
        dedupeWindowMs: Long = 5_000L,
    ) {
        if (!audioEnabled) return
        if (!initialized.get()) {
            Log.e(TAG, "[SpeechManager.speakIfAllowed] TTS not initialized")
            return
        }

        val normalized = text.trim().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return

        val now = SystemClock.elapsedRealtime()

        // "let it first say the current input": Do not interrupt if currently speaking
        if (speaking.get()) {
            return
        }

        // "send the next input after 2 seconds": Global rate limit between any speech
        if ((now - lastSpokenAtMs) < 2000L) {
            return
        }

        // Deduplicate: don't repeat the exact same phrase within the window
        if (lastSpoken == normalized && (now - lastSpokenAtMs) < dedupeWindowMs) {
            Log.d(TAG, "[SpeechManager.speakIfAllowed] skipped (dedupe)")
            return
        }

        val utteranceId = "utt_${now}"
        currentUtteranceId = utteranceId
        currentUtteranceStartMs = now
        speaking.set(true)
        tts.speak(normalized, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

        // Safety: force-stop if still speaking after max duration
        mainHandler.postDelayed({
            if (speaking.get() && currentUtteranceId == utteranceId) {
                val elapsed = SystemClock.elapsedRealtime() - currentUtteranceStartMs
                if (elapsed >= MAX_UTTERANCE_MS) {
                    Log.d(TAG, "[SpeechManager] max duration, stopping $utteranceId")
                    tts.stop()
                    speaking.set(false)
                }
            }
        }, MAX_UTTERANCE_MS + 100L)

        lastSpoken = normalized
        lastSpokenAtMs = now
    }

    fun shutdown() {
        Log.d(TAG, "[SpeechManager.shutdown]")
        tts.stop()
        tts.shutdown()
    }

    fun isSpeaking(): Boolean = speaking.get()
}
