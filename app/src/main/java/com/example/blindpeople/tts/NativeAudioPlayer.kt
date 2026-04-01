package com.example.blindpeople.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams raw PCM audio (24 kHz, 16-bit, mono) directly to the speaker.
 * Replaces SpeechManager for the Native Audio Live API architecture.
 *
 * Call [write] with Base64-encoded PCM chunks as they arrive from the
 * Gemini WebSocket. Call [stop] to flush and silence. Call [release] on destroy.
 */
class NativeAudioPlayer {

    companion object {
        private const val TAG = "BlindPeopleLog"
        private const val SAMPLE_RATE = 24_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private val playing = AtomicBoolean(false)

    private val audioTrack: AudioTrack by lazy {
        val bufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .setEncoding(ENCODING)
                    .build()
            )
            .setBufferSizeInBytes(bufSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /**
     * Decode a Base64-encoded PCM chunk and write it to the AudioTrack.
     * Starts playback automatically on the first chunk.
     */
    fun write(base64Pcm: String) {
        try {
            val pcmBytes = Base64.decode(base64Pcm, Base64.DEFAULT)
            if (pcmBytes.isEmpty()) return

            if (!playing.getAndSet(true)) {
                audioTrack.play()
                Log.d(TAG, "[NativeAudioPlayer] playback started")
            }

            audioTrack.write(pcmBytes, 0, pcmBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "[NativeAudioPlayer.write] error", e)
        }
    }

    /**
     * Stop playback and flush any buffered audio. Safe to call multiple times.
     */
    fun stop() {
        if (playing.getAndSet(false)) {
            try {
                audioTrack.pause()
                audioTrack.flush()
                Log.d(TAG, "[NativeAudioPlayer] stopped")
            } catch (e: Exception) {
                Log.e(TAG, "[NativeAudioPlayer.stop] error", e)
            }
        }
    }

    /**
     * Release the underlying AudioTrack resources. Call on ViewModel.onCleared().
     */
    fun release() {
        stop()
        try {
            audioTrack.release()
            Log.d(TAG, "[NativeAudioPlayer] released")
        } catch (e: Exception) {
            Log.e(TAG, "[NativeAudioPlayer.release] error", e)
        }
    }
}
