package com.example

import android.annotation.SuppressLint
import android.util.Size
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.activity.compose.BackHandler
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private fun normalizeScannedExamUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.contains(" ")) return null
    val candidate = if (
        trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
        trimmed
    } else if (trimmed.contains(".")) {
        "https://$trimmed"
    } else {
        return null
    }
    return if (candidate.startsWith("http://", ignoreCase = true) || candidate.startsWith("https://", ignoreCase = true)) {
        candidate
    } else {
        null
    }
}

@SuppressLint("ClickableViewAccessibility")
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
fun QrScannerScreen(
    onQrCodeScanned: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var hasFlash by remember { mutableStateOf(false) }
    var isTorchOn by remember { mutableStateOf(false) }
    var currentZoomRatio by remember { mutableFloatStateOf(1f) }
    var maxZoomRatio by remember { mutableFloatStateOf(4f) }
    var invalidQrHint by remember { mutableStateOf<String?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    val hasDeliveredResult = remember { AtomicBoolean(false) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }

    val scanner = remember {
        val zoomOptions = ZoomSuggestionOptions.Builder { suggestedZoom ->
            val cam = boundCamera ?: return@Builder false
            val target = suggestedZoom.coerceIn(1f, maxZoomRatio)
            mainHandler.post {
                try {
                    cam.cameraControl.setZoomRatio(target)
                    currentZoomRatio = target
                } catch (_: Exception) {
                }
            }
            true
        }
            .setMaxSupportedZoomRatio(4f)
            .build()

        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .setZoomSuggestionOptions(zoomOptions)
            .build()
        BarcodeScanning.getClient(options)
    }

    BackHandler(enabled = true) {
        onClose()
    }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var activeProvider: ProcessCameraProvider? = null
        var lastInvalidHintMs = 0L

        val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = boundCamera ?: return false
                    val nextZoom = (currentZoomRatio * detector.scaleFactor).coerceIn(1f, maxZoomRatio)
                    cam.cameraControl.setZoomRatio(nextZoom)
                    currentZoomRatio = nextZoom
                    return true
                }
            }
        )

        previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {
                val cam = boundCamera
                if (cam != null) {
                    try {
                        val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                        val action = FocusMeteringAction.Builder(
                            point,
                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                        )
                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                            .build()
                        cam.cameraControl.startFocusAndMetering(action)
                    } catch (_: Exception) {
                    }
                }
            }
            true
        }

        val listener = Runnable {
            try {
                val cameraProvider = cameraProviderFuture.get()
                activeProvider = cameraProvider

                val preview = Preview.Builder()
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage == null || hasDeliveredResult.get()) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    val inputImage = InputImage.fromMediaImage(
                        mediaImage,
                        imageProxy.imageInfo.rotationDegrees
                    )

                    scanner.process(inputImage)
                        .addOnSuccessListener { barcodes ->
                            if (hasDeliveredResult.get()) return@addOnSuccessListener
                            for (barcode in barcodes) {
                                val rawValue = barcode.rawValue?.trim().orEmpty()
                                if (rawValue.isEmpty()) continue
                                val validUrl = normalizeScannedExamUrl(rawValue)
                                if (validUrl != null) {
                                    if (hasDeliveredResult.compareAndSet(false, true)) {
                                        mainHandler.post {
                                            onQrCodeScanned(validUrl)
                                        }
                                    }
                                    break
                                } else {
                                    val now = System.currentTimeMillis()
                                    if (now - lastInvalidHintMs > 2000L) {
                                        lastInvalidHintMs = now
                                        mainHandler.post {
                                            invalidQrHint = "QR bukan tautan ujian: $rawValue"
                                        }
                                    }
                                }
                            }
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                }

                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                boundCamera = camera
                hasFlash = camera.cameraInfo.hasFlashUnit()
                val zoomState = camera.cameraInfo.zoomState.value
                maxZoomRatio = (zoomState?.maxZoomRatio ?: 4f).coerceAtMost(5f)
            } catch (e: Exception) {
                cameraError = "Kamera tidak dapat dibuka: ${e.localizedMessage ?: "Coba lagi"}"
            }
        }

        cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            try {
                boundCamera?.cameraControl?.enableTorch(false)
            } catch (_: Exception) {
            }
            try {
                activeProvider?.unbindAll()
            } catch (_: Exception) {
            }
            try {
                scanner.close()
            } catch (_: Exception) {
            }
            try {
                analysisExecutor.shutdownNow()
            } catch (_: Exception) {
            }
        }
    }

    val laserTransition = rememberInfiniteTransition(label = "qr_laser")
    val laserProgress by laserTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laser_y"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay viewfinder with emerald corner brackets & animated scan line
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            val boxSize = (minOf(size.width, size.height) * 0.68f).coerceAtMost(900f)
            val left = (size.width - boxSize) / 2f
            val top = (size.height - boxSize) / 2f
            val right = left + boxSize
            val bottom = top + boxSize
            val cornerRadius = 28f

            drawRect(color = Color.Black.copy(alpha = 0.48f))
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(boxSize, boxSize),
                cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                blendMode = BlendMode.Clear
            )

            val bracketLen = boxSize * 0.14f
            val strokeW = 10f
            val bracketColor = Color(0xFF4CAF50)

            // Top-left
            drawLine(bracketColor, Offset(left, top + bracketLen), Offset(left, top), strokeW, cap = StrokeCap.Round)
            drawLine(bracketColor, Offset(left, top), Offset(left + bracketLen, top), strokeW, cap = StrokeCap.Round)
            // Top-right
            drawLine(bracketColor, Offset(right - bracketLen, top), Offset(right, top), strokeW, cap = StrokeCap.Round)
            drawLine(bracketColor, Offset(right, top), Offset(right, top + bracketLen), strokeW, cap = StrokeCap.Round)
            // Bottom-left
            drawLine(bracketColor, Offset(left, bottom - bracketLen), Offset(left, bottom), strokeW, cap = StrokeCap.Round)
            drawLine(bracketColor, Offset(left, bottom), Offset(left + bracketLen, bottom), strokeW, cap = StrokeCap.Round)
            // Bottom-right
            drawLine(bracketColor, Offset(right - bracketLen, bottom), Offset(right, bottom), strokeW, cap = StrokeCap.Round)
            drawLine(bracketColor, Offset(right, bottom - bracketLen), Offset(right, bottom), strokeW, cap = StrokeCap.Round)

            // Subtle frame outline
            drawRoundRect(
                color = Color.White.copy(alpha = 0.28f),
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(boxSize, boxSize),
                cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                style = Stroke(width = 2f)
            )

            // Animated scan laser line
            val laserY = top + (boxSize * laserProgress)
            drawLine(
                color = Color(0xFF69F0AE).copy(alpha = 0.85f),
                start = Offset(left + 20f, laserY),
                end = Offset(right - 20f, laserY),
                strokeWidth = 5f,
                cap = StrokeCap.Round
            )
        }

        // Top controls (Back, Title, Torch)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalIconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("qr_back_button"),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Kembali ke Dashboard"
                )
            }

            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(99.dp)
            ) {
                Text(
                    text = "Scan QR Ujian",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (hasFlash) {
                FilledTonalIconButton(
                    onClick = {
                        val nextTorch = !isTorchOn
                        try {
                            boundCamera?.cameraControl?.enableTorch(nextTorch)
                            isTorchOn = nextTorch
                        } catch (_: Exception) {
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("qr_torch_button"),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isTorchOn) Color(0xFF2E7D32) else Color.Black.copy(alpha = 0.6f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = if (isTorchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        contentDescription = if (isTorchOn) "Matikan Senter" else "Nyalakan Senter"
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(48.dp))
            }
        }

        // Bottom controls & instructions
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 20.dp)
                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val errorOrHint = cameraError ?: invalidQrHint
            if (!errorOrHint.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = errorOrHint,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(1f, 2f).forEach { zoomTarget ->
                    val isSelected = kotlin.math.abs(currentZoomRatio - zoomTarget) < 0.35f
                    Surface(
                        onClick = {
                            val clamped = zoomTarget.coerceIn(1f, maxZoomRatio)
                            try {
                                boundCamera?.cameraControl?.setZoomRatio(clamped)
                                currentZoomRatio = clamped
                            } catch (_: Exception) {
                            }
                        },
                        shape = CircleShape,
                        color = if (isSelected) Color(0xFF2E7D32) else Color.Black.copy(alpha = 0.65f),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (zoomTarget > 1f) {
                                Icon(
                                    imageVector = Icons.Filled.ZoomIn,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = "${zoomTarget.toInt()}x",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = "Arahkan kamera ke QR Code pengawas • Ketuk layar untuk fokus",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}
