package com.example.blindpeople.ui

import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.blindpeople.camera.toBase64NoWrap
import com.example.blindpeople.camera.toJpegByteArray
import com.example.blindpeople.data.GeminiUnaryClient
import com.example.blindpeople.tts.SpeechManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val geminiClient: GeminiUnaryClient,
) : ViewModel() {

    companion object {
        private const val TAG = "BlindPeopleLog"
    }

    private val speechManager = SpeechManager(appContext)

    private val _uiState = MutableStateFlow<AppUiState>(AppUiState.Idle)
    val uiState: StateFlow<AppUiState> = _uiState

    private var running = false
    private var audioEnabled = true
    private val isProcessing = AtomicBoolean(false)

    fun setAudioEnabled(enabled: Boolean) {
        Log.d(TAG, "[MainViewModel.setAudioEnabled] enabled=$enabled")
        audioEnabled = enabled
        if (!enabled) {
            speechManager.stopSpeaking()
        }
        updateRunningState()
    }

    fun start() {
        Log.d(TAG, "[MainViewModel.start] called")
        if (!hasInternet()) {
            _uiState.value = AppUiState.Error("No internet connection", recoverable = true)
            return
        }

        running = true
        _uiState.value = AppUiState.Running(
            status = "Listening…",
            audioEnabled = audioEnabled
        )
    }

    fun stop() {
        Log.d(TAG, "[MainViewModel.stop] called")
        running = false
        speechManager.stopSpeaking()
        isProcessing.set(false)
        _uiState.value = AppUiState.Idle
    }

    /**
     * Compress the frame and send it to the Unary REST API.
     * Only send if not currently processing and not speaking.
     */
    fun onFrame(bitmap: Bitmap) {
        if (!running) return

        // Skip if we are currently analyzing a frame or if SpeechManager is actively talking
        if (isProcessing.get() || speechManager.isSpeaking()) {
            return
        }

        // Lock
        if (!isProcessing.compareAndSet(false, true)) {
            return
        }

        _uiState.value = AppUiState.Running(
            status = "Analyzing…",
            audioEnabled = audioEnabled
        )

        viewModelScope.launch {
            try {
                val b64 = bitmap.toJpegByteArray(quality = 30, maxDimension = 320).toBase64NoWrap()
                val result = geminiClient.detectObject(b64)
                
                if (result != null && running) {
                    Log.d(TAG, "[MainViewModel] Gemini result: $result")
                    speechManager.speakIfAllowed(result, audioEnabled)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[MainViewModel] error detecting object", e)
            } finally {
                // Unlock and update UI state
                isProcessing.set(false)
                if (running) {
                    _uiState.value = AppUiState.Running(
                        status = "Listening…",
                        audioEnabled = audioEnabled
                    )
                }
            }
        }
    }

    private fun updateRunningState() {
        if (running) {
            _uiState.value = AppUiState.Running(
                status = (_uiState.value as? AppUiState.Running)?.status ?: "Listening…",
                audioEnabled = audioEnabled
            )
        }
    }

    private fun hasInternet(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "[MainViewModel.onCleared]")
        speechManager.shutdown()
    }
}
