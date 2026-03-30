package com.example.blindpeople.camera

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

class FrameAnalyzer(
    private val maxFps: Double = 3.0,
    private val onFrame: (android.graphics.Bitmap) -> Unit,
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "BlindPeopleLog"
    }
    private val minIntervalMs: Long = (1000.0 / maxFps).toLong().coerceAtLeast(1L)
    @Volatile private var lastAnalyzedAtMs: Long = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalyzedAtMs < minIntervalMs) {
                return // Silently drop — no need to log every dropped frame
            }
            lastAnalyzedAtMs = now

            val bmp = image.toJpegBitmap(quality = 70) ?: return
            Log.d(TAG, "[FrameAnalyzer.analyze] frame accepted size=${bmp.width}x${bmp.height}")
            onFrame(bmp)
        } finally {
            image.close()
        }
    }
}
