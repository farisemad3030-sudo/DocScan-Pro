package com.docscan.app.core

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.toArgb
import com.docscan.app.model.DocFilter
import com.docscan.app.model.DrawPath
import com.docscan.app.model.PageAnnotations
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

object PdfManager {

    fun saveInternalPdf(context: Context, sourcePdfUri: Uri, baseName: String): File {
        val docsDir = File(context.filesDir, "scanned_documents").apply { mkdirs() }
        val targetFile = File(docsDir, "${baseName}.pdf")

        context.contentResolver.openInputStream(sourcePdfUri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
        return targetFile
    }

    fun exportPdfToPublicDownloads(context: Context, sourceFile: File, title: String): Uri? {
        val cleanTitle = sanitizeFileName(title)
        val fileName = if (cleanTitle.endsWith(".pdf", ignoreCase = true)) cleanTitle else "${cleanTitle}.pdf"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DocScan")
            }

            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
                return uri
            }
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DocScan")
            downloadsDir.mkdirs()
            val destFile = File(downloadsDir, fileName)
            sourceFile.copyTo(destFile, overwrite = true)
            return Uri.fromFile(destFile)
        }
        return null
    }

    /**
     * Efficiently decodes image with memory-safe downsampling for instant high-speed PDF rendering.
     */
    fun decodeOptimizedBitmap(context: Context, uri: Uri, maxDimension: Int = 1800): Bitmap? {
        try {
            // First pass: decode bounds only
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }

            val origWidth = options.outWidth
            val origHeight = options.outHeight
            if (origWidth <= 0 || origHeight <= 0) return null

            // Calculate sample size
            var inSampleSize = 1
            val maxSide = max(origWidth, origHeight)
            while ((maxSide / inSampleSize) > maxDimension) {
                inSampleSize *= 2
            }

            // Second pass: decode actual sampled bitmap
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            return context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * Builds a PDF with full filter enhancements and baked-in annotations (pen, highlighter, text).
     */
    fun createPdfWithAnnotations(
        context: Context,
        imageUris: List<Uri>,
        annotationsMap: Map<Int, PageAnnotations>,
        filter: DocFilter,
        outputTitle: String
    ): File {
        val cleanTitle = sanitizeFileName(outputTitle)
        val pdfDocument = PdfDocument()
        val docsDir = File(context.filesDir, "scanned_documents").apply { mkdirs() }
        val outputFile = File(docsDir, "${cleanTitle}.pdf")

        for ((index, uri) in imageUris.withIndex()) {
            val rawBitmap = decodeOptimizedBitmap(context, uri, maxDimension = 1800)

            if (rawBitmap != null) {
                // 1. Apply image enhancement filter
                val filteredBitmap = ImageFilterManager.applyFilter(rawBitmap, filter)

                // 2. Prepare mutable bitmap to bake annotations
                val annotatedBitmap = filteredBitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(annotatedBitmap)

                // 3. Render drawn paths and highlighters
                val pageAnnotation = annotationsMap[index]
                if (pageAnnotation != null) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                    }

                    for (drawPath in pageAnnotation.paths) {
                        paint.color = drawPath.color.toArgb()
                        paint.strokeWidth = drawPath.strokeWidth * (annotatedBitmap.width / 500f)
                        if (drawPath.isHighlighter) {
                            paint.alpha = 110 // Translucent highlighter
                        } else {
                            paint.alpha = 255
                        }

                        val points = drawPath.points
                        if (points.size > 1) {
                            val path = android.graphics.Path()
                            path.moveTo(points[0].x * annotatedBitmap.width, points[0].y * annotatedBitmap.height)
                            for (i in 1 until points.size) {
                                path.lineTo(points[i].x * annotatedBitmap.width, points[i].y * annotatedBitmap.height)
                            }
                            canvas.drawPath(path, paint)
                        }
                    }

                    // 4. Render text annotations
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 28f * (annotatedBitmap.width / 500f)
                        isFakeBoldText = true
                    }
                    for (textNote in pageAnnotation.textNotes) {
                        textPaint.color = textNote.color.toArgb()
                        canvas.drawText(
                            textNote.text,
                            textNote.position.x * annotatedBitmap.width,
                            textNote.position.y * annotatedBitmap.height,
                            textPaint
                        )
                    }
                }

                // 5. Add page to PDF
                val pageInfo = PdfDocument.PageInfo.Builder(annotatedBitmap.width, annotatedBitmap.height, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                page.canvas.drawBitmap(annotatedBitmap, 0f, 0f, null)
                pdfDocument.finishPage(page)

                annotatedBitmap.recycle()
                if (filteredBitmap != rawBitmap) filteredBitmap.recycle()
                rawBitmap.recycle()
            }
        }

        FileOutputStream(outputFile).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()

        return outputFile
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
    }

    fun generateDefaultDocTitle(): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        return "DocScan_${sdf.format(Date())}"
    }
}
