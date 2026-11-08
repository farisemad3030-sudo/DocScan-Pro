package com.docscan.app.core

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs

object DocumentAutoCropper {

    /**
     * Detects document boundaries in a bitmap and returns the cropped document bitmap.
     */
    fun autoCropDocument(rawBitmap: Bitmap): Bitmap {
        try {
            val width = rawBitmap.width
            val height = rawBitmap.height
            if (width <= 100 || height <= 100) return rawBitmap

            // Downsample for ultra-fast edge analysis
            val sampleWidth = 320
            val sampleHeight = (height * 320f / width).toInt().coerceAtLeast(200)
            val sampleBitmap = Bitmap.createScaledBitmap(rawBitmap, sampleWidth, sampleHeight, true)

            val detectedRect = detectDocumentBoundary(sampleBitmap, sampleWidth, sampleHeight)
            if (sampleBitmap != rawBitmap) {
                sampleBitmap.recycle()
            }

            // Map sample rect back to original full-resolution dimensions
            val scaleX = width.toFloat() / sampleWidth
            val scaleY = height.toFloat() / sampleHeight

            val cropLeft = (detectedRect.left * scaleX).toInt().coerceIn(0, width - 50)
            val cropTop = (detectedRect.top * scaleY).toInt().coerceIn(0, height - 50)
            val cropRight = (detectedRect.right * scaleX).toInt().coerceIn(cropLeft + 50, width)
            val cropBottom = (detectedRect.bottom * scaleY).toInt().coerceIn(cropTop + 50, height)

            val cropWidth = cropRight - cropLeft
            val cropHeight = cropBottom - cropTop

            // Return cropped sub-bitmap
            return Bitmap.createBitmap(rawBitmap, cropLeft, cropTop, cropWidth, cropHeight)
        } catch (e: Exception) {
            e.printStackTrace()
            return rawBitmap
        }
    }

    /**
     * Analyzes pixel luminance, gradient, and variance across rows/columns to locate paper edges.
     */
    private fun detectDocumentBoundary(bitmap: Bitmap, w: Int, h: Int): Rect {
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Convert to grayscale luminance
        val lum = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lum[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        // Scan from Left towards Center
        var left = 0
        val maxScanX = (w * 0.28f).toInt()
        for (x in 2 until maxScanX) {
            var diffSum = 0f
            for (y in (h * 0.15f).toInt() until (h * 0.85f).toInt()) {
                val idx = y * w + x
                val diff = abs(lum[idx] - lum[idx - 1])
                diffSum += diff
            }
            val avgDiff = diffSum / (h * 0.7f)
            if (avgDiff > 14f) {
                left = x
                break
            }
        }

        // Scan from Right towards Center
        var right = w - 1
        val minScanX = (w * 0.72f).toInt()
        for (x in (w - 3) downTo minScanX) {
            var diffSum = 0f
            for (y in (h * 0.15f).toInt() until (h * 0.85f).toInt()) {
                val idx = y * w + x
                val diff = abs(lum[idx] - lum[idx + 1])
                diffSum += diff
            }
            val avgDiff = diffSum / (h * 0.7f)
            if (avgDiff > 14f) {
                right = x
                break
            }
        }

        // Scan from Top towards Center
        var top = 0
        val maxScanY = (h * 0.25f).toInt()
        for (y in 2 until maxScanY) {
            var diffSum = 0f
            for (x in (w * 0.15f).toInt() until (w * 0.85f).toInt()) {
                val idx = y * w + x
                val diff = abs(lum[idx] - lum[idx - w])
                diffSum += diff
            }
            val avgDiff = diffSum / (w * 0.7f)
            if (avgDiff > 14f) {
                top = y
                break
            }
        }

        // Scan from Bottom towards Center
        var bottom = h - 1
        val minScanY = (h * 0.75f).toInt()
        for (y in (h - 3) downTo minScanY) {
            var diffSum = 0f
            for (x in (w * 0.15f).toInt() until (w * 0.85f).toInt()) {
                val idx = y * w + x
                val diff = abs(lum[idx] - lum[idx + w])
                diffSum += diff
            }
            val avgDiff = diffSum / (w * 0.7f)
            if (avgDiff > 14f) {
                bottom = y
                break
            }
        }

        // Ensure minimum document size (at least 45% of width and height)
        val finalLeft = if (right - left < w * 0.45f) (w * 0.03f).toInt() else left
        val finalRight = if (right - left < w * 0.45f) (w * 0.97f).toInt() else right
        val finalTop = if (bottom - top < h * 0.45f) (h * 0.03f).toInt() else top
        val finalBottom = if (bottom - top < h * 0.45f) (h * 0.97f).toInt() else bottom

        return Rect(finalLeft, finalTop, finalRight, finalBottom)
    }
}
