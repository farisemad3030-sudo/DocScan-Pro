package com.docscan.app.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.docscan.app.model.DocFilter

object ImageFilterManager {

    fun getFilterColorMatrix(filter: DocFilter): ColorMatrix? {
        return when (filter) {
            DocFilter.ORIGINAL -> null
            DocFilter.MAGIC_COLOR -> {
                // Magic Color: High contrast + Brightness boost + Saturation boost for crisp text on clean white paper
                val satMatrix = ColorMatrix().apply { setSaturation(1.35f) }
                val contrastMatrix = createContrastBrightnessMatrix(contrast = 1.45f, brightness = 30f)
                satMatrix.postConcat(contrastMatrix)
                satMatrix
            }
            DocFilter.BLACK_AND_WHITE -> {
                // High Contrast B&W (Threshold look for sharp black text on pure white paper)
                val grayMatrix = ColorMatrix().apply { setSaturation(0f) }
                val contrastMatrix = createContrastBrightnessMatrix(contrast = 2.4f, brightness = 15f)
                grayMatrix.postConcat(contrastMatrix)
                grayMatrix
            }
            DocFilter.CLEAN_PAGE -> {
                // Clean Paper: Moderate contrast to remove table shadow while preserving color ink
                createContrastBrightnessMatrix(contrast = 1.3f, brightness = 35f)
            }
            DocFilter.GRAYSCALE -> {
                ColorMatrix().apply { setSaturation(0f) }
            }
        }
    }

    fun applyFilter(source: Bitmap, filter: DocFilter): Bitmap {
        val matrix = getFilterColorMatrix(filter) ?: return source

        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }

        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    private fun createContrastBrightnessMatrix(contrast: Float, brightness: Float): ColorMatrix {
        val scale = contrast
        val translate = (-0.5f * scale + 0.5f) * 255f + brightness
        return ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
    }
}
