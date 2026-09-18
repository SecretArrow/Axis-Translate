package com.axis.translate.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.axis.translate.domain.model.TranslationResult
import com.axis.translate.ui.components.EmptyState
import com.axis.translate.ui.components.ErrorBanner
import com.axis.translate.ui.components.LoadingOverlay
import com.axis.translate.ui.navigation.PendingInput
import com.axis.translate.util.AndroidUtils
import com.axis.translate.util.rememberContainer
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Camera screen (SPEC #12): live preview with still capture, gallery pick and
 * one-shot live OCR, then an editable review step and a translation result.
 */
@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    val container = rememberContainer()
    val vm: CameraViewModel = viewModel(factory = CameraViewModel.factory(container))
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    // Permission is never auto-requested: the preview area shows the rationale
    // and the user explicitly taps "Grant camera access".
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }

    // Images shared into the app from other apps land here.
    LaunchedEffect(Unit) {
        PendingInput.sharedImageUri.collect { uri ->
            if (uri != null) {
                val bitmap = withContext(Dispatchers.IO) { ImageUtils.decodeDownsampled(context, uri) }
                vm.onImageReady(bitmap, fromCamera = false)
                PendingInput.sharedImageUri.value = null
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (val phase = state.phase) {
            CameraPhase.Previewing -> CameraPreviewContent(
                state = state,
                hasPermission = hasPermission,
                onGrantPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onImageCaptured = vm::onImageCaptured,
                onImageReady = { vm.onImageReady(it, fromCamera = false) },
                onLiveChanged = vm::setLive,
                onFlashChanged = vm::setFlash,
                onCameraError = vm::onCameraError,
            )

            is CameraPhase.Reviewing -> OcrReview(
                bitmap = phase.bitmap,
                editedText = phase.editedText,
                hasLowConfidence = phase.ocr.hasLowConfidence,
                busy = state.recognizing,
                onUpdateText = vm::updateEditedOcr,
                onRetry = vm::retryOcr,
                onTranslate = vm::translateOcr,
            )

            CameraPhase.Translating -> TranslatingContent(onCancel = vm::cancelTranslation)

            is CameraPhase.Done -> DoneContent(
                sourceText = phase.sourceText,
                result = phase.result,
                onCopy = { AndroidUtils.copyToClipboard(context, phase.result.translatedText) },
                onShare = { AndroidUtils.shareText(context, phase.result.translatedText) },
                onSpeak = { container.textSpeaker.speak(phase.result.translatedText, state.target.code) },
                onNewScan = { vm.reset() },
            )

            is CameraPhase.Failed -> FailedContent(message = phase.message, onRetry = { vm.reset() })
        }

        state.error?.let { message ->
            ErrorBanner(
                message = message,
                onDismiss = { vm.dismissError() },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun CameraPreviewContent(
    state: CameraUiState,
    hasPermission: Boolean,
    onGrantPermission: () -> Unit,
    onImageCaptured: (File) -> Unit,
    onImageReady: (Bitmap?) -> Unit,
    onLiveChanged: (Boolean) -> Unit,
    onFlashChanged: (Boolean) -> Unit,
    onCameraError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var pendingGalleryUri by remember { mutableStateOf<Uri?>(null) }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pendingGalleryUri = uri
    }

    if (!hasPermission) {
        PermissionContent(onGrant = onGrantPermission, modifier = modifier)
        return
    }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val preview = remember { Preview.Builder().build() }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    val imageAnalysis = remember {
        ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
    }
    val executor = remember { ContextCompat.getMainExecutor(context) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // Decode a gallery pick off the main thread before handing it to OCR.
    LaunchedEffect(pendingGalleryUri) {
        val uri = pendingGalleryUri ?: return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) { ImageUtils.decodeDownsampled(context, uri) }
        onImageReady(bitmap)
        pendingGalleryUri = null
    }

    // (Re)bind the camera whenever live mode flips; the image analysis use
    // case is only attached while live mode is on.
    LaunchedEffect(state.liveEnabled, lifecycleOwner) {
        val provider = withContext(Dispatchers.IO) {
            ProcessCameraProvider.getInstance(context).get()
        }
        cameraProvider = provider
        provider.unbindAll()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val useCases = mutableListOf<UseCase>(preview, imageCapture)
        if (state.liveEnabled) {
            imageAnalysis.setAnalyzer(executor, LiveFrameAnalyzer { bitmap ->
                // Scene changed: run one-shot OCR, then stop analyzing until re-enabled.
                onImageReady(bitmap)
                onLiveChanged(false)
            })
            useCases += imageAnalysis
        } else {
            imageAnalysis.clearAnalyzer()
        }
        camera = try {
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                *useCases.toTypedArray(),
            )
        } catch (e: Exception) {
            onCameraError()
            null
        }
    }

    LaunchedEffect(state.flashOn, camera) {
        camera?.cameraControl?.enableTorch(state.flashOn)
    }

    // Leaving this screen must release the camera (battery / privacy indicator).
    DisposableEffect(lifecycleOwner) {
        onDispose {
            cameraProvider?.unbindAll()
            camera = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = state.liveEnabled,
                onClick = { onLiveChanged(!state.liveEnabled) },
                label = { Text("Live") },
            )
            Text(
                text = "${state.source.code.uppercase()} → ${state.target.code.uppercase()}",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 36.dp, vertical = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = "Pick image from gallery", tint = Color.White)
            }
            FloatingActionButton(
                onClick = {
                    if (state.recognizing) return@FloatingActionButton
                    val photoFile = File(context.cacheDir, "axis_${System.currentTimeMillis()}.jpg")
                    imageCapture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(photoFile).build(),
                        executor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                onImageCaptured(photoFile)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                onCameraError()
                            }
                        },
                    )
                },
                modifier = Modifier.size(72.dp),
            ) {
                Icon(Icons.Rounded.PhotoCamera, contentDescription = "Take photo", modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = { onFlashChanged(!state.flashOn) }) {
                Icon(
                    imageVector = if (state.flashOn) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                    contentDescription = if (state.flashOn) "Turn flash off" else "Turn flash on",
                    tint = Color.White,
                )
            }
        }

        if (state.recognizing) {
            LoadingOverlay(visible = true, label = "Reading text…")
        }
    }
}

@Composable
private fun PermissionContent(onGrant: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyState(
            icon = Icons.Outlined.PhotoCamera,
            title = "Camera access needed",
            subtitle = "Grant permission to translate with your camera",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGrant) {
            Text("Grant camera access")
        }
    }
}

@Composable
private fun OcrReview(
    bitmap: Bitmap?,
    editedText: String,
    hasLowConfidence: Boolean,
    busy: Boolean,
    onUpdateText: (String) -> Unit,
    onRetry: () -> Unit,
    onTranslate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Captured photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .height(240.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp)),
                )
                Spacer(Modifier.height(16.dp))
            }
            OutlinedTextField(
                value = editedText,
                onValueChange = onUpdateText,
                label = { Text("Detected Text") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            if (hasLowConfidence) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Some words may be uncertain.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onRetry, modifier = Modifier.weight(1f)) {
                    Text("Retry OCR")
                }
                Button(
                    onClick = onTranslate,
                    enabled = editedText.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Translate All")
                }
            }
        }
        if (busy) {
            LoadingOverlay(visible = true, label = "Reading text…")
        }
    }
}

@Composable
private fun TranslatingContent(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        LoadingOverlay(visible = true, label = "Translating…")
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(180.dp))
            OutlinedButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun DoneContent(
    sourceText: String,
    result: TranslationResult,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSpeak: () -> Unit,
    onNewScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = "Source",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = sourceText,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(Modifier.padding(16.dp)) {
                result.detectedLanguage?.let { detected ->
                    Text(
                        text = "Detected: ${detected.displayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                SelectionContainer {
                    Text(
                        text = result.translatedText,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onCopy) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy translation")
                    }
                    IconButton(onClick = onShare) {
                        Icon(Icons.Rounded.Share, contentDescription = "Share translation")
                    }
                    IconButton(onClick = onSpeak) {
                        Icon(Icons.Rounded.VolumeUp, contentDescription = "Speak translation")
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onNewScan, modifier = Modifier.fillMaxWidth()) {
            Text("New Scan")
        }
    }
}

@Composable
private fun FailedContent(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ErrorBanner(message = message, onDismiss = onRetry)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Text("Try again")
        }
    }
}

/**
 * Analyzer for one-shot live OCR: frames are throttled to
 * [MIN_FRAME_INTERVAL_MS] and skipped while their 16×16 average-luminance
 * signature stays close to the last seen one — so an actual scene change is
 * what triggers recognition.
 */
private class LiveFrameAnalyzer(
    private val onSceneChange: (Bitmap) -> Unit,
) : ImageAnalysis.Analyzer {

    private var lastProcessedMs = 0L
    private var lastSignature: IntArray? = null

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastProcessedMs < MIN_FRAME_INTERVAL_MS) return
            val signature = luminanceSignature(image)
            if (signature != null) {
                val previous = lastSignature
                if (previous != null && meanAbsoluteDifference(previous, signature) < SCENE_CHANGE_DELTA) {
                    return
                }
                lastSignature = signature
            }
            lastProcessedMs = now
            val bitmap = runCatching { image.toBitmap() }.getOrNull() ?: return
            onSceneChange(bitmap)
        } finally {
            image.close()
        }
    }

    /** 16×16 grid of average luminance values sampled from the Y plane. */
    private fun luminanceSignature(image: ImageProxy): IntArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val signature = IntArray(GRID * GRID)
        for (cellY in 0 until GRID) {
            for (cellX in 0 until GRID) {
                var sum = 0L
                var count = 0
                val x0 = cellX * width / GRID
                val x1 = (cellX + 1) * width / GRID
                val y0 = cellY * height / GRID
                val y1 = (cellY + 1) * height / GRID
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) {
                        sum += (buffer.get(y * plane.rowStride + x).toInt() and 0xFF).toLong()
                        count++
                        x += SAMPLING_STEP
                    }
                    y += SAMPLING_STEP
                }
                signature[cellY * GRID + cellX] = if (count > 0) (sum / count).toInt() else 0
            }
        }
        return signature
    }

    private fun meanAbsoluteDifference(a: IntArray, b: IntArray): Int {
        if (a.size != b.size) return Int.MAX_VALUE
        var total = 0L
        for (i in a.indices) {
            total += abs(a[i] - b[i])
        }
        return (total / a.size).toInt()
    }

    companion object {
        private const val GRID = 16
        private const val SAMPLING_STEP = 4
        private const val MIN_FRAME_INTERVAL_MS = 200L
        private const val SCENE_CHANGE_DELTA = 14
    }
}
