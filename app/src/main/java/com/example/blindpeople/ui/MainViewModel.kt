package com.example.blindpeople.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.blindpeople.data.DetectedObject
import com.example.blindpeople.data.LabelTranslator
import com.example.blindpeople.detector.DistanceEstimator
import com.example.blindpeople.detector.ObjectDetectorHelper
import com.example.blindpeople.tts.SpeechManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import android.content.Context
import androidx.lifecycle.ViewModel
import java.util.Locale

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val objectDetector: ObjectDetectorHelper,
    private val labelTranslator: LabelTranslator,
) : ViewModel() {

    companion object {
        private const val TAG = "BlindPeopleLog"
        private const val DISTANCE_THRESHOLD_M = 7.0
        private const val MAX_OBJECTS_TO_SPEAK = 2  // Reduced from 3 to avoid verbose alerts
        private const val DEDUPE_WINDOW_MS = 5_000L // 5 seconds to avoid repetitive alerts
    }

    private val speech = SpeechManager(appContext)
    private val distanceEstimator = DistanceEstimator()

    private val _uiState = MutableStateFlow<AppUiState>(
        AppUiState.Idle
    )
    val uiState: StateFlow<AppUiState> = _uiState

    private var running = false
    private var audioEnabled = true
    private var selectedLanguage = "en"

    @Volatile private var inFlight: Job? = null

    // Direction words per language (hardcoded — simple and always the same 3 words)
    private val directionWords = mapOf(
        "en" to Triple("ahead", "on the left", "on the right"),
        "hi" to Triple("आगे", "बाएं", "दाएं"),
        "mr" to Triple("पुढे", "डावीकडे", "उजवीकडे"),
    )

    // Removed distanceUnit map

    fun setAudioEnabled(enabled: Boolean) {
        Log.d(TAG, "[MainViewModel.setAudioEnabled] enabled=$enabled")
        audioEnabled = enabled
        _uiState.update { state ->
            when (state) {
                is AppUiState.Running -> state.copy(audioEnabled = enabled)
                else -> state
            }
        }
    }

    fun setLanguage(langCode: String) {
        Log.d(TAG, "[MainViewModel.setLanguage] langCode=$langCode")
        selectedLanguage = langCode
        val locale = when (langCode) {
            "hi" -> Locale("hi", "IN")
            "mr" -> Locale("mr", "IN")
            else -> Locale.US
        }
        speech.setLanguage(locale)
        _uiState.update { state ->
            when (state) {
                is AppUiState.Running -> state.copy(selectedLanguage = langCode)
                else -> state
            }
        }
    }

    fun start() {
        Log.d(TAG, "[MainViewModel.start] called")
        running = true
        _uiState.value = AppUiState.Running(
            status = "Starting…",
            audioEnabled = audioEnabled,
            selectedLanguage = selectedLanguage
        )
    }

    fun stop() {
        Log.d(TAG, "[MainViewModel.stop] called")
        running = false
        inFlight?.cancel()
        inFlight = null
        speech.stopSpeaking()
        _uiState.value = AppUiState.Idle
    }

    fun onFrame(bitmap: Bitmap) {
        if (!running) return

        // Skip if detection already in-flight
        val currentJob = inFlight
        if (currentJob != null && currentJob.isActive) return

        inFlight = viewModelScope.launch {
            // 1. Run on-device detection (no API, ~50-80ms)
            val nearbyObjects = withContext(Dispatchers.Default) {
                val startMs = System.currentTimeMillis()
                val detections = objectDetector.detect(bitmap)
                val elapsedMs = System.currentTimeMillis() - startMs
                Log.d("Time-Taken", "[MainViewModel] detection in ${elapsedMs}ms, found ${detections.size} objects")

                detections.mapNotNull { detection ->
                    val label = detection.categories.firstOrNull()?.label ?: return@mapNotNull null
                    val confidence = detection.categories.firstOrNull()?.score ?: 0f
                    val distance = distanceEstimator.estimateDistance(
                        detection, bitmap.width, bitmap.height
                    )
                    val position = distanceEstimator.getPosition(detection, bitmap.width)

                    Log.d(TAG, "[MainViewModel] detected: $label (${(confidence * 100).toInt()}%) ${distance.toInt()}m $position")

                    DetectedObject(
                        name = label,
                        estimated_distance_m = distance,
                        position = position
                    )
                }
                .filter { it.estimated_distance_m <= DISTANCE_THRESHOLD_M }
                .sortedBy { it.estimated_distance_m }
                .take(MAX_OBJECTS_TO_SPEAK)
            }

            // Update UI status (always in English for display)
            _uiState.value = AppUiState.Running(
                status = summarizeStatus(nearbyObjects),
                audioEnabled = audioEnabled,
                selectedLanguage = selectedLanguage
            )

            // 2. Build and speak alerts
            if (nearbyObjects.isNotEmpty() && audioEnabled) {
                val speechText = buildSpeech(nearbyObjects)
                if (speechText != null) {
                    Log.d(TAG, "[MainViewModel.onFrame] speaking: $speechText")
                    speech.speakIfAllowed(
                        text = speechText,
                        audioEnabled = audioEnabled,
                        dedupeWindowMs = DEDUPE_WINDOW_MS
                    )
                }
            }
        }
    }

    /**
     * Build speech text from detected objects.
     * For non-English: translates labels via Gemini API (cached after first call).
     */
    private suspend fun buildSpeech(objects: List<DetectedObject>): String? {
        if (objects.isEmpty()) return null

        // Translate labels if language is not English
        val translatedNames: Map<String, String> = if (selectedLanguage != "en") {
            try {
                labelTranslator.translate(
                    labels = objects.map { it.name },
                    targetLangCode = selectedLanguage
                )
            } catch (e: Exception) {
                Log.e(TAG, "[MainViewModel.buildSpeech] translation failed, using English", e)
                objects.associate { it.name to it.name }
            }
        } else {
            objects.associate { it.name to it.name }
        }

        val alerts = objects.map { obj ->
            val translatedName = translatedNames[obj.name] ?: obj.name
            formatAlert(translatedName, obj.estimated_distance_m, obj.position)
        }

        return alerts.joinToString(". ")
    }

    private fun formatAlert(name: String, distanceM: Double, position: String): String {
        val dirs = directionWords[selectedLanguage] ?: directionWords["en"]!!
        return "$name ${dirs.first}"
    }

    private fun summarizeStatus(objects: List<DetectedObject>): String {
        return if (objects.isEmpty()) {
            "No objects detected"
        } else {
            "Nearby: " + objects.joinToString { it.name }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "[MainViewModel.onCleared]")
        speech.shutdown()
        objectDetector.close()
    }
}
