package com.example.blindpeople.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

internal fun ImageProxy.toJpegBitmap(quality: Int = 80): Bitmap? {
    // Only supports YUV_420_888, which is the default output of ImageAnalysis.
    if (format != ImageFormat.YUV_420_888) return null
    val nv21 = yuv420ToNv21(this) ?: return null
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    val ok = yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, out)
    if (!ok) return null
    val jpegBytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
}

private fun yuv420ToNv21(image: ImageProxy): ByteArray? {
    val yPlane = image.planes.getOrNull(0) ?: return null
    val uPlane = image.planes.getOrNull(1) ?: return null
    val vPlane = image.planes.getOrNull(2) ?: return null

    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    // NV21 format: yyyyyyyy vu vu vu...
    val nv21 = ByteArray(ySize + uSize + vSize)

    yBuffer.get(nv21, 0, ySize)

    // Interleave V and U. The U/V planes in YUV_420_888 can have row/pixel strides.
    // We handle the general case by reading based on strides.
    val width = image.width
    val height = image.height
    val chromaWidth = width / 2
    val chromaHeight = height / 2

    val vRowStride = vPlane.rowStride
    val vPixelStride = vPlane.pixelStride
    val uRowStride = uPlane.rowStride
    val uPixelStride = uPlane.pixelStride

    val vBytes = ByteArray(vBuffer.remaining()).also { vBuffer.get(it) }
    val uBytes = ByteArray(uBuffer.remaining()).also { uBuffer.get(it) }

    var outputOffset = ySize
    for (row in 0 until chromaHeight) {
        var vRowOffset = row * vRowStride
        var uRowOffset = row * uRowStride
        for (col in 0 until chromaWidth) {
            val vIndex = vRowOffset + col * vPixelStride
            val uIndex = uRowOffset + col * uPixelStride
            if (outputOffset + 1 < nv21.size &&
                vIndex in vBytes.indices &&
                uIndex in uBytes.indices
            ) {
                nv21[outputOffset++] = vBytes[vIndex]
                nv21[outputOffset++] = uBytes[uIndex]
            }
        }
    }

    return nv21
}

internal fun Bitmap.toJpegByteArray(quality: Int = 80, maxDimension: Int? = null): ByteArray {
    val scaledBitmap = if (maxDimension != null && (width > maxDimension || height > maxDimension)) {
        val scale = maxDimension.toFloat() / Math.max(width, height)
        Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
    } else {
        this
    }
    
    val out = ByteArrayOutputStream()
    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
    if (scaledBitmap != this) {
        scaledBitmap.recycle()
    }
    return out.toByteArray()
}

internal fun ByteArray.toBase64NoWrap(): String =
    android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)

