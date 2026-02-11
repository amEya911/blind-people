package com.example.blindpeople.tts

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
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
    }

    private val tts = TextToSpeech(context.applicationContext, this)
    private val initialized = AtomicBoolean(false)

    private var lastSpoken: String? = null
    private var lastSpokenAtMs: Long = 0L

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            Log.d(TAG, "[SpeechManager.onInit] SUCCESS")
            tts.language = Locale.US
            initialized.set(true)
            _ready.value = true
        } else {
            Log.e(TAG, "[SpeechManager.onInit] FAILURE status=$status")
            initialized.set(false)
            _ready.value = false
        }
    }

    @Synchronized
    fun speakIfAllowed(
        text: String,
        audioEnabled: Boolean,
        dedupeWindowMs: Long = 7_000L,
    ) {
        if (!audioEnabled) {
            Log.d(TAG, "[SpeechManager.speakIfAllowed] audio disabled")
            return
        }
        if (!initialized.get()) {
            Log.e(TAG, "[SpeechManager.speakIfAllowed] TTS not initialized")
            return
        }

        val normalized = text.trim().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return

        val now = SystemClock.elapsedRealtime()
        if (lastSpoken == normalized && (now - lastSpokenAtMs) < dedupeWindowMs) {
            Log.d(TAG, "[SpeechManager.speakIfAllowed] skipped due to dedupe window, text=$normalized")
            return
        }

        // Cancel overlapping speech deterministically.
        tts.stop()
        tts.speak(normalized, TextToSpeech.QUEUE_FLUSH, null, "utterance_${now}")

        lastSpoken = normalized
        lastSpokenAtMs = now
    }

    fun shutdown() {
        Log.d(TAG, "[SpeechManager.shutdown]")
        tts.stop()
        tts.shutdown()
    }
}

