package com.example.blindpeople.detector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector

/**
 * Wraps TensorFlow Lite ObjectDetector for on-device inference.
 * Uses EfficientDet-Lite2 trained on COCO (80 object classes).
 * More accurate than Lite0 — reduces false positives like banana/blanket confusion.
 * Typical inference time: 50–80ms on modern phones.
 */
class ObjectDetectorHelper(
    context: Context,
    modelFileName: String = MODEL_FILE,
    maxResults: Int = MAX_RESULTS,
    scoreThreshold: Float = SCORE_THRESHOLD,
    numThreads: Int = NUM_THREADS,
) {
    companion object {
        private const val TAG = "BlindPeopleLog"
        private const val MODEL_FILE = "efficientdet_lite4.tflite"
        private const val MAX_RESULTS = 10
        private const val SCORE_THRESHOLD = 0.25f  // Lowered from 0.50 — EfficientDet outputs valid detections at 0.25-0.45
        private const val NUM_THREADS = 4
    }

    private val detector: ObjectDetector

    init {
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setMaxResults(maxResults)
            .setScoreThreshold(scoreThreshold)
            .setNumThreads(numThreads)
            .build()
        detector = ObjectDetector.createFromFileAndOptions(
            context, modelFileName, options
        )
        Log.d(TAG, "[ObjectDetectorHelper] initialized model=$modelFileName threshold=$scoreThreshold")
    }

    /**
     * Run object detection on a Bitmap. Returns detections sorted by confidence.
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        val tensorImage = TensorImage.fromBitmap(bitmap)
        val results = detector.detect(tensorImage)
        Log.d(TAG, "[ObjectDetectorHelper.detect] found ${results.size} objects")
        return results
    }

    fun close() {
        detector.close()
    }
}
