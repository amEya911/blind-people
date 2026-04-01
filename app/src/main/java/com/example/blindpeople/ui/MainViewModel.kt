package com.example.blindpeople.ui

import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.blindpeople.camera.toBase64NoWrap
import com.example.blindpeople.camera.toJpegByteArray
import com.example.blindpeople.data.GeminiLiveSession
import com.example.blindpeople.tts.NativeAudioPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.content.Context

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val liveSession: GeminiLiveSession,
) : ViewModel() {

    companion object {
        private const val TAG = "BlindPeopleLog"
    }

    private val audioPlayer = NativeAudioPlayer()

    private val _uiState = MutableStateFlow<AppUiState>(AppUiState.Idle)
    val uiState: StateFlow<AppUiState> = _uiState

    private var running = false
    private var audioEnabled = true
    private var responseCollectorJob: Job? = null

    fun setAudioEnabled(enabled: Boolean) {
        Log.d(TAG, "[MainViewModel.setAudioEnabled] enabled=$enabled")
        audioEnabled = enabled
        if (!enabled) {
            audioPlayer.stop()
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
            status = "Connecting…",
            audioEnabled = audioEnabled
        )

        // Open the WebSocket
        liveSession.connect()

        // Collect audio chunks and pipe them to the AudioTrack player
        responseCollectorJob?.cancel()
        responseCollectorJob = viewModelScope.launch {
            liveSession.audioChunks.collect { base64Pcm ->
                if (audioEnabled) {
                    audioPlayer.write(base64Pcm)
                }
            }
        }

        // Collect turn completions for UI status updates
        viewModelScope.launch {
            liveSession.turnComplete.collect {
                Log.d(TAG, "[MainViewModel] turn complete")
                if (running) {
                    _uiState.value = AppUiState.Running(
                        status = "Listening…",
                        audioEnabled = audioEnabled
                    )
                }
            }
        }
    }

    fun stop() {
        Log.d(TAG, "[MainViewModel.stop] called")
        running = false
        responseCollectorJob?.cancel()
        responseCollectorJob = null
        liveSession.disconnect()
        audioPlayer.stop()
        _uiState.value = AppUiState.Idle
    }

    /**
     * Fire-and-forget: compress the frame and send it to the WebSocket.
     * The model will respond asynchronously with audio chunks.
     */
    fun onFrame(bitmap: Bitmap) {
        if (!running) return

        if (!liveSession.isConnected()) {
            // Session not ready yet — update status and skip this frame
            _uiState.value = AppUiState.Running(
                status = "Connecting…",
                audioEnabled = audioEnabled
            )
            return
        }

        _uiState.value = AppUiState.Running(
            status = "Streaming…",
            audioEnabled = audioEnabled
        )

        // Compress and send — fire-and-forget, no blocking
        val b64 = bitmap.toJpegByteArray(quality = 50, maxDimension = 640).toBase64NoWrap()
        liveSession.sendFrame(b64)
    }

    private fun updateRunningState() {
        if (running) {
            _uiState.value = AppUiState.Running(
                status = (_uiState.value as? AppUiState.Running)?.status ?: "Streaming…",
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
        liveSession.disconnect()
        audioPlayer.release()
    }
}
