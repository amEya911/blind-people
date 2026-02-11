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
        private const val MAX_UTTERANCE_MS = 5_000L
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
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "[SpeechManager] onStart id=$utteranceId")
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

        // Cancel overlapping speech deterministically, and track new utterance.
        tts.stop()
        val utteranceId = "utterance_${now}"
        currentUtteranceId = utteranceId
        currentUtteranceStartMs = now
        speaking.set(true)
        tts.speak(normalized, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

        // Enforce max 5s duration by scheduling a stop if still speaking.
        mainHandler.postDelayed({
            val stillSpeaking = speaking.get()
            val sameUtterance = currentUtteranceId == utteranceId
            val elapsed = SystemClock.elapsedRealtime() - currentUtteranceStartMs
            if (stillSpeaking && sameUtterance && elapsed >= MAX_UTTERANCE_MS) {
                Log.d(TAG, "[SpeechManager] max duration reached, stopping utteranceId=$utteranceId")
                tts.stop()
                speaking.set(false)
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

