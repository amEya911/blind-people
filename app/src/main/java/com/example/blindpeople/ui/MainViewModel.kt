package com.example.blindpeople.ui

import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.blindpeople.camera.toBase64NoWrap
import com.example.blindpeople.camera.toJpegByteArray
import com.example.blindpeople.data.DetectedObject
import com.example.blindpeople.data.GeminiRepository
import com.example.blindpeople.data.VisionResult
import com.example.blindpeople.tts.SpeechManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.content.Context
import androidx.lifecycle.ViewModel
import java.util.Locale

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repo: GeminiRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "BlindPeopleLog"
        private const val DISTANCE_THRESHOLD_M = 7.0
        private const val MAX_OBJECTS_TO_SPEAK = 3
    }

    private val speech = SpeechManager(appContext)

    private val _uiState = MutableStateFlow<AppUiState>(
        AppUiState.Idle
    )
    val uiState: StateFlow<AppUiState> = _uiState

    private var running = false
    private var audioEnabled = true
    private var selectedLanguage = "en"

    @Volatile private var inFlight: Job? = null

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

        if (!hasInternet()) {
            Log.e(TAG, "[MainViewModel.onFrame] No internet connection")
            _uiState.value = AppUiState.Error("No internet connection", recoverable = true)
            return
        }

        // If a request is already in-flight, drop this frame.
        // But do NOT gate on isSpeaking() — allow analysis during speech for lower latency.
        val currentJob = inFlight
        if (currentJob != null && currentJob.isActive) {
            return
        }

        Log.d(TAG, "[MainViewModel.onFrame] launching analysis, bitmap=${bitmap.width}x${bitmap.height}")
        inFlight = viewModelScope.launch {
            _uiState.update { state ->
                when (state) {
                    is AppUiState.Running -> state.copy(status = "Analyzing…")
                    else -> AppUiState.Running(
                        status = "Analyzing…",
                        audioEnabled = audioEnabled,
                        selectedLanguage = selectedLanguage
                    )
                }
            }

            // Smaller payload for faster upload: quality=20, max 480px
            val b64 = bitmap.toJpegByteArray(quality = 20, maxDimension = 480).toBase64NoWrap()
            val result = repo.analyzeImageJpegBase64(b64)
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message ?: "API error"
                Log.e(TAG, "[MainViewModel.onFrame] analysis failure: $msg", result.exceptionOrNull())
                _uiState.value = AppUiState.Error(msg, recoverable = true)
                return@launch
            }

            val vision = result.getOrThrow()
            Log.d(TAG, "[MainViewModel.onFrame] vision objects=${vision.objects.size}")
            val speechText = buildSpeech(vision)
            _uiState.value = AppUiState.Running(
                status = summarizeStatus(vision),
                audioEnabled = audioEnabled,
                selectedLanguage = selectedLanguage
            )

            if (speechText != null) {
                // Interrupt stale speech with fresher detection
                speech.stopSpeaking()
                Log.d(TAG, "[MainViewModel.onFrame] speaking: $speechText")
                speech.speakIfAllowed(
                    text = speechText,
                    audioEnabled = audioEnabled,
                    dedupeWindowMs = 4_000L
                )
            }
        }
    }

    /**
     * Build speech from detected objects only (no raw text).
     * Filters to objects within 7m, takes top 3 nearest, and generates
     * directional alerts in the selected language.
     */
    private fun buildSpeech(vision: VisionResult): String? {
        val nearby = vision.objects
            .filter { it.estimated_distance_m <= DISTANCE_THRESHOLD_M }
            .sortedBy { it.estimated_distance_m }
            .take(MAX_OBJECTS_TO_SPEAK)

        if (nearby.isEmpty()) {
            Log.d(TAG, "[MainViewModel.buildSpeech] no objects within ${DISTANCE_THRESHOLD_M}m")
            return null
        }

        val alerts = nearby.map { obj -> formatObjectAlert(obj) }
        return alerts.joinToString(". ")
    }

    /**
     * Format a single object alert with direction and distance in the selected language.
     */
    private fun formatObjectAlert(obj: DetectedObject): String {
        val distStr = "%.0f".format(obj.estimated_distance_m)
        return when (selectedLanguage) {
            "hi" -> {
                val dir = when (obj.position.lowercase()) {
                    "left" -> "बाएं"
                    "right" -> "दाएं"
                    else -> "आगे"
                }
                "${obj.name}, $distStr मीटर $dir"
            }
            "mr" -> {
                val dir = when (obj.position.lowercase()) {
                    "left" -> "डावीकडे"
                    "right" -> "उजवीकडे"
                    else -> "पुढे"
                }
                "${obj.name}, $distStr मीटर $dir"
            }
            else -> {
                val dir = when (obj.position.lowercase()) {
                    "left" -> "on the left"
                    "right" -> "on the right"
                    else -> "ahead"
                }
                "${obj.name}, $distStr meters $dir"
            }
        }
    }

    private fun summarizeStatus(vision: VisionResult): String {
        val within = vision.objects
            .filter { it.estimated_distance_m <= DISTANCE_THRESHOLD_M }
            .sortedBy { it.estimated_distance_m }
            .take(3)
        return if (within.isEmpty()) {
            "No nearby objects (≤${DISTANCE_THRESHOLD_M.toInt()}m)"
        } else {
            "Nearby: " + within.joinToString {
                "${it.name} (~${"%.1f".format(it.estimated_distance_m)}m ${it.position})"
            }
        }
    }

    private fun hasInternet(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "[MainViewModel.onCleared]")
        speech.shutdown()
    }
}
