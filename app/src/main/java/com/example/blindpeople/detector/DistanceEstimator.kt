package com.example.blindpeople.detector

import org.tensorflow.lite.task.vision.detector.Detection
import kotlin.math.tan

/**
 * Estimates object distance from bounding box size using the pinhole camera model,
 * and determines horizontal position (left / center / right) from bbox center X.
 *
 * Distance formula: distance = (realHeight × frameHeight) / (bboxHeight × 2 × tan(VFOV/2))
 *
 * This is approximate but sufficient for safety alerts (close / medium / far).
 */
class DistanceEstimator {

    companion object {
        /**
         * Typical real-world heights (meters) for COCO object classes.
         * Used as the reference dimension for pinhole-model distance estimation.
         */
        private val KNOWN_HEIGHTS: Map<String, Double> = mapOf(
            "person" to 1.7,
            "bicycle" to 1.0,
            "car" to 1.5,
            "motorcycle" to 1.1,
            "airplane" to 4.0,
            "bus" to 3.2,
            "train" to 3.5,
            "truck" to 3.0,
            "boat" to 1.5,
            "traffic light" to 0.6,
            "fire hydrant" to 0.6,
            "stop sign" to 0.75,
            "parking meter" to 1.2,
            "bench" to 0.9,
            "bird" to 0.2,
            "cat" to 0.3,
            "dog" to 0.5,
            "horse" to 1.6,
            "sheep" to 0.7,
            "cow" to 1.4,
            "elephant" to 3.0,
            "bear" to 1.5,
            "zebra" to 1.4,
            "giraffe" to 5.0,
            "backpack" to 0.5,
            "umbrella" to 1.0,
            "handbag" to 0.3,
            "tie" to 0.5,
            "suitcase" to 0.6,
            "frisbee" to 0.03,
            "skis" to 1.7,
            "snowboard" to 0.3,
            "sports ball" to 0.22,
            "kite" to 0.8,
            "baseball bat" to 1.0,
            "baseball glove" to 0.25,
            "skateboard" to 0.15,
            "surfboard" to 0.6,
            "tennis racket" to 0.7,
            "bottle" to 0.25,
            "wine glass" to 0.2,
            "cup" to 0.12,
            "fork" to 0.18,
            "knife" to 0.25,
            "spoon" to 0.18,
            "bowl" to 0.1,
            "banana" to 0.18,
            "apple" to 0.08,
            "sandwich" to 0.08,
            "orange" to 0.08,
            "broccoli" to 0.2,
            "carrot" to 0.2,
            "hot dog" to 0.05,
            "pizza" to 0.05,
            "donut" to 0.05,
            "cake" to 0.15,
            "chair" to 0.9,
            "couch" to 0.85,
            "potted plant" to 0.5,
            "bed" to 0.6,
            "dining table" to 0.75,
            "toilet" to 0.7,
            "tv" to 0.5,
            "laptop" to 0.3,
            "mouse" to 0.04,
            "remote" to 0.2,
            "keyboard" to 0.05,
            "cell phone" to 0.14,
            "microwave" to 0.35,
            "oven" to 0.85,
            "toaster" to 0.2,
            "sink" to 0.4,
            "refrigerator" to 1.7,
            "book" to 0.24,
            "clock" to 0.3,
            "vase" to 0.3,
            "scissors" to 0.2,
            "teddy bear" to 0.4,
            "hair drier" to 0.25,
            "toothbrush" to 0.18,
        )

        /** Fallback height for objects not in the lookup table */
        private const val DEFAULT_HEIGHT_M = 0.8

        /**
         * Approximate vertical field of view for a typical smartphone rear camera.
         * ~50 degrees in radians. Adjust if you know your specific camera's FOV.
         */
        private const val CAMERA_VFOV_RAD = 0.873 // 50°
    }

    /**
     * Estimate the distance (meters) to a detected object using the pinhole camera model.
     *
     * @param detection  TFLite detection with bounding box
     * @param frameWidth  Width of the camera frame in pixels
     * @param frameHeight Height of the camera frame in pixels
     * @return estimated distance in meters, clamped to [0.3, 15.0]
     */
    fun estimateDistance(
        detection: Detection,
        frameWidth: Int,
        frameHeight: Int,
    ): Double {
        val bbox = detection.boundingBox
        val bboxHeight = bbox.height()
        if (bboxHeight <= 0f) return 15.0 // effectively "far away"

        val label = detection.categories.firstOrNull()?.label?.lowercase() ?: ""
        val realHeight = KNOWN_HEIGHTS[label] ?: DEFAULT_HEIGHT_M

        // Pinhole model: d = (H_real × F_pixels) / H_bbox
        // where F_pixels ≈ frameHeight / (2 × tan(VFOV / 2))
        val focalPixels = frameHeight / (2.0 * tan(CAMERA_VFOV_RAD / 2.0))
        val distance = (realHeight * focalPixels) / bboxHeight

        return distance.coerceIn(0.3, 15.0)
    }

    /**
     * Determine horizontal position of the object relative to the camera frame.
     *
     * @return "left", "center", or "right"
     */
    fun getPosition(detection: Detection, frameWidth: Int): String {
        val centerX = detection.boundingBox.centerX()
        val third = frameWidth / 3.0f
        return when {
            centerX < third -> "left"
            centerX > third * 2f -> "right"
            else -> "center"
        }
    }
}
