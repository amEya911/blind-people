package com.example.blindpeople.ui

import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.blindpeople.camera.toBase64NoWrap
import com.example.blindpeople.camera.toJpegByteArray
import com.example.blindpeople.data.OpenAiRepository
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

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repo: OpenAiRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "BlindPeopleLog"
    }

    private val speech = SpeechManager(appContext)

    private val _uiState = MutableStateFlow<AppUiState>(
        AppUiState.Idle
    )
    val uiState: StateFlow<AppUiState> = _uiState

    private var running = false
    private var audioEnabled = true

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

    fun start() {
        Log.d(TAG, "[MainViewModel.start] called")
        running = true
        _uiState.value = AppUiState.Running(status = "Starting…", audioEnabled = audioEnabled)
    }

    fun stop() {
        Log.d(TAG, "[MainViewModel.stop] called")
        running = false
        inFlight?.cancel()
        inFlight = null
        _uiState.value = AppUiState.Idle
    }

    fun onFrame(bitmap: Bitmap) {
        if (!running) {
            Log.d(TAG, "[MainViewModel.onFrame] ignored, not running")
            return
        }
        if (!hasInternet()) {
            Log.e(TAG, "[MainViewModel.onFrame] No internet connection")
            _uiState.value = AppUiState.Error("No internet connection", recoverable = true)
            return
        }

        // If a request is already in-flight, drop this frame to guarantee completion and speech.
        val currentJob = inFlight
        if (currentJob != null && currentJob.isActive) {
            Log.d(TAG, "[MainViewModel.onFrame] analysis already in-flight, dropping frame")
            return
        }

        Log.d(TAG, "[MainViewModel.onFrame] launching analysis job, bitmap=${bitmap.width}x${bitmap.height}")
        inFlight = viewModelScope.launch {
            _uiState.update { state ->
                when (state) {
                    is AppUiState.Running -> state.copy(status = "Analyzing…", audioEnabled = audioEnabled)
                    else -> AppUiState.Running(status = "Analyzing…", audioEnabled = audioEnabled)
                }
            }

            val b64 = bitmap.toJpegByteArray(quality = 75).toBase64NoWrap()
            val result = repo.analyzeImageJpegBase64(b64)
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message ?: "API error"
                Log.e(TAG, "[MainViewModel.onFrame] analysis failure: $msg", result.exceptionOrNull())
                _uiState.value = AppUiState.Error(msg, recoverable = true)
                return@launch
            }

            val vision = result.getOrThrow()
            Log.d(TAG, "[MainViewModel.onFrame] vision objects=${vision.objects.size} textCount=${vision.text.size}")
            val speechText = buildSpeech(vision)
            _uiState.value = AppUiState.Running(
                status = summarizeStatus(vision),
                audioEnabled = audioEnabled
            )

            if (speechText != null) {
                Log.d(TAG, "[MainViewModel.onFrame] speaking: $speechText")
                speech.speakIfAllowed(
                    text = speechText,
                    audioEnabled = audioEnabled,
                    dedupeWindowMs = 7_000L
                )
            }
        }
    }

    private fun buildSpeech(vision: VisionResult): String? {
        val nearestWithin5m = vision.objects
            .filter { it.estimated_distance_m <= 5.0 }
            .minByOrNull { it.estimated_distance_m }

        val objectPart = nearestWithin5m?.let { "${it.name} ahead" }

        val textPart = vision.text
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(separator = ". ")
            .takeIf { it.isNotBlank() }
            ?.let { "Text reads: $it" }

        // CRITICAL:
        // - Speak objects ONLY when estimated_distance_m <= 5.
        // - Text is always allowed if present.
        return when {
            objectPart != null && textPart != null -> "$objectPart. $textPart"
            objectPart != null -> objectPart
            textPart != null -> textPart
            else -> {
                Log.d(TAG, "[MainViewModel.buildSpeech] nothing to say")
                null
            }
        }
    }

    private fun summarizeStatus(vision: VisionResult): String {
        val within = vision.objects.filter { it.estimated_distance_m <= 5.0 }
            .sortedBy { it.estimated_distance_m }
            .take(3)
        val objSummary = if (within.isEmpty()) {
            "No nearby objects (≤5m)."
        } else {
            "Nearby: " + within.joinToString { "${it.name} (~${"%.1f".format(it.estimated_distance_m)}m)" }
        }
        val textSummary = if (vision.text.isEmpty()) "No text." else "Text detected."
        return "$objSummary $textSummary"
    }

    private fun hasInternet(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        val has = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        Log.d(TAG, "[MainViewModel.hasInternet] has=$has")
        return has
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "[MainViewModel.onCleared]")
        speech.shutdown()
    }
}

