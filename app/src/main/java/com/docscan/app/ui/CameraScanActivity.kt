package com.docscan.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.docscan.app.core.DocumentAutoCropper
import com.docscan.app.core.PdfManager
import com.docscan.app.ui.theme.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraScanActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    private val capturedImageUris = mutableStateListOf<Uri>()
    private var isAutoCropEnabled by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setContent {
            DocScanTheme {
                CameraScanScreen(
                    capturedUris = capturedImageUris,
                    isAutoCrop = isAutoCropEnabled,
                    onToggleAutoCrop = { isAutoCropEnabled = !isAutoCropEnabled },
                    onBindCamera = { previewView, isFlashOn ->
                        bindCamera(previewView, isFlashOn)
                    },
                    onToggleFlash = { isFlashOn ->
                        camera?.cameraControl?.enableTorch(isFlashOn)
                    },
                    onCaptureClick = {
                        takePhoto()
                    },
                    onFinishClick = {
                        if (capturedImageUris.isNotEmpty()) {
                            finishAndOpenViewer()
                        }
                    },
                    onBackClick = { finish() }
                )
            }
        }
    }

    private fun bindCamera(previewView: PreviewView, isFlashOn: Boolean) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )
                camera?.cameraControl?.enableTorch(isFlashOn)
            } catch (exc: Exception) {
                Toast.makeText(this, "Failed to start camera", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = this.imageCapture ?: return
        val cacheDir = File(cacheDir, "scan_captures").apply { mkdirs() }
        val photoFile = File(cacheDir, "scan_${System.currentTimeMillis()}.jpg")

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Haptic feedback
        @Suppress("DEPRECATION")
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        }

        val autoCrop = isAutoCropEnabled

        imageCapture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (autoCrop) {
                        try {
                            val rawBmp = BitmapFactory.decodeFile(photoFile.absolutePath)
                            if (rawBmp != null) {
                                val cropped = DocumentAutoCropper.autoCropDocument(rawBmp)
                                FileOutputStream(photoFile).use { out ->
                                    cropped.compress(Bitmap.CompressFormat.JPEG, 92, out)
                                }
                                if (cropped != rawBmp) cropped.recycle()
                                rawBmp.recycle()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    val savedUri = Uri.fromFile(photoFile)
                    runOnUiThread {
                        capturedImageUris.add(savedUri)
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    runOnUiThread {
                        Toast.makeText(this@CameraScanActivity, "Capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun finishAndOpenViewer() {
        val title = PdfManager.generateDefaultDocTitle()
        val intent = Intent(this, DocumentViewerActivity::class.java).apply {
            putExtra(DocumentViewerActivity.EXTRA_DOC_TITLE, title)
            putStringArrayListExtra(
                DocumentViewerActivity.EXTRA_PAGE_URIS,
                ArrayList(capturedImageUris.map { it.toString() })
            )
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

@Composable
fun CameraScanScreen(
    capturedUris: List<Uri>,
    isAutoCrop: Boolean,
    onToggleAutoCrop: () -> Unit,
    onBindCamera: (PreviewView, Boolean) -> Unit,
    onToggleFlash: (Boolean) -> Unit,
    onCaptureClick: () -> Unit,
    onFinishClick: () -> Unit,
    onBackClick: () -> Unit
) {
    var isFlashOn by remember { mutableStateOf(false) }
    var shutterAnimTrigger by remember { mutableStateOf(false) }

    val shutterScale by animateFloatAsState(
        targetValue = if (shutterAnimTrigger) 0.85f else 1f,
        animationSpec = tween(durationMillis = 100),
        finishedListener = { shutterAnimTrigger = false },
        label = "shutterScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Camera Viewfinder
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    post { onBindCamera(this, isFlashOn) }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Live Auto-Crop Frame Overlay
        if (isAutoCrop) {
            AutoCropViewfinderFrame(modifier = Modifier.fillMaxSize())
        }

        // Top Control Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White
                )
            }

            // Center: Auto-Crop Mode Badge Button
            Surface(
                onClick = onToggleAutoCrop,
                shape = RoundedCornerShape(20.dp),
                color = if (isAutoCrop) EmeraldPrimary else Color.Black.copy(alpha = 0.6f),
                modifier = Modifier.height(36.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Crop,
                        contentDescription = "Auto Crop",
                        tint = if (isAutoCrop) SlateBackground else Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isAutoCrop) "Auto-Crop: ON" else "Auto-Crop: OFF",
                        color = if (isAutoCrop) SlateBackground else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Flash Toggle Button
            IconButton(
                onClick = {
                    isFlashOn = !isFlashOn
                    onToggleFlash(isFlashOn)
                },
                modifier = Modifier
                    .size(44.dp)
                    .background(if (isFlashOn) EmeraldPrimary else Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = "Flash",
                    tint = if (isFlashOn) SlateBackground else Color.White
                )
            }
        }

        // Bottom Capture & Review Bar
        Surface(
            color = Color.Black.copy(alpha = 0.75f),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Animated Thumbnail Badge Counter
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SlateSurface)
                        .border(1.5.dp, if (capturedUris.isNotEmpty()) EmeraldPrimary else Color.Transparent, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (capturedUris.isNotEmpty()) {
                        AsyncImage(
                            model = capturedUris.last(),
                            contentDescription = "Latest Thumbnail",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        // Badge Count Overlay
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 4.dp, y = (-4).dp)
                                .background(EmeraldPrimary, CircleShape)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${capturedUris.size}",
                                color = SlateBackground,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // Center: Rapid-Fire Shutter Button
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f))
                        .padding(4.dp)
                        .scale(shutterScale)
                        .clickable {
                            shutterAnimTrigger = true
                            onCaptureClick()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }

                // Right: Finish / Done Button
                Button(
                    onClick = onFinishClick,
                    enabled = capturedUris.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldPrimary,
                        disabledContainerColor = Color.Gray.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (capturedUris.isNotEmpty()) "Done (${capturedUris.size})" else "Done",
                            fontWeight = FontWeight.Bold,
                            color = if (capturedUris.isNotEmpty()) SlateBackground else Color.DarkGray,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = if (capturedUris.isNotEmpty()) SlateBackground else Color.DarkGray,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AutoCropViewfinderFrame(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.padding(horizontal = 28.dp, vertical = 90.dp)) {
        val w = size.width
        val h = size.height
        val cornerLen = 36.dp.toPx()
        val strokeW = 3.5.dp.toPx()
        val cornerColor = Color(0xFF10B981) // Emerald Primary
        val boxColor = Color.White.copy(alpha = 0.25f)

        // Bounding Rectangle with subtle dashed outline
        drawRoundRect(
            color = boxColor,
            topLeft = Offset.Zero,
            size = Size(w, h),
            cornerRadius = CornerRadius(16.dp.toPx()),
            style = Stroke(
                width = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
            )
        )

        // 4 Glowing Corner Brackets
        // Top-Left
        drawLine(cornerColor, Offset(0f, 0f), Offset(cornerLen, 0f), strokeW)
        drawLine(cornerColor, Offset(0f, 0f), Offset(0f, cornerLen), strokeW)

        // Top-Right
        drawLine(cornerColor, Offset(w, 0f), Offset(w - cornerLen, 0f), strokeW)
        drawLine(cornerColor, Offset(w, 0f), Offset(w, cornerLen), strokeW)

        // Bottom-Left
        drawLine(cornerColor, Offset(0f, h), Offset(cornerLen, h), strokeW)
        drawLine(cornerColor, Offset(0f, h), Offset(0f, h - cornerLen), strokeW)

        // Bottom-Right
        drawLine(cornerColor, Offset(w, h), Offset(w - cornerLen, h), strokeW)
        drawLine(cornerColor, Offset(w, h), Offset(w, h - cornerLen), strokeW)
    }
}
