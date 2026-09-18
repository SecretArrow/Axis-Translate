package com.axis.translate.ui.photo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.axis.translate.ui.camera.ImageUtils
import com.axis.translate.ui.components.EmptyState
import com.axis.translate.ui.components.ErrorBanner
import com.axis.translate.ui.components.LoadingOverlay
import com.axis.translate.ui.navigation.PendingInput
import com.axis.translate.util.AndroidUtils
import com.axis.translate.util.rememberContainer
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Photo translation screen: shared-image intake, pinch/rotate/pan viewer with
 * OCR region overlays, tap-to-translate regions, translate-all, and a
 * SPLIT original/translated comparison.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoTranslateScreen(modifier: Modifier = Modifier) {
    val container = rememberContainer()
    val vm: PhotoTranslateViewModel = viewModel(factory = PhotoTranslateViewModel.factory(container))
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current

    // Decode failure of the shared image is screen-local (no VM slot needed).
    var imageError by remember { mutableStateOf<String?>(null) }

    // Intake: image shared into the app (PendingInput) lands here.
    LaunchedEffect(Unit) {
        PendingInput.sharedImageUri.collect { uri ->
            if (uri != null) {
                val bitmap = withContext(Dispatchers.IO) {
                    ImageUtils.decodeDownsampled(context, uri)
                }
                if (bitmap != null) {
                    imageError = null
                    vm.onImage(bitmap)
                } else {
                    imageError = "Could not read image"
                }
                PendingInput.sharedImageUri.value = null
            }
        }
    }

    val bitmap = state.bitmap
    if (bitmap == null) {
        Box(modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                val message = imageError ?: state.error
                if (message != null) {
                    ErrorBanner(
                        message = message,
                        onDismiss = {
                            imageError = null
                            vm.dismissError()
                        }
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    EmptyState(
                        icon = Icons.Outlined.Image,
                        title = "Photo translation",
                        subtitle = "Share an image with Axis Translate or use the camera",
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
            LoadingOverlay(visible = state.translating, label = "Translating…")
        }
        return
    }

    // Viewer transform state, reset whenever a new image arrives.
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var rotation by remember(bitmap) { mutableFloatStateOf(0f) }
    val transformState = rememberTransformableState { zoomChange, panChange, rotChange ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset += panChange
        rotation += rotChange
    }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            state.error?.let { error ->
                ErrorBanner(message = error, onDismiss = { vm.dismissError() })
            }

            // --- Image viewer with OCR region overlays ---
            val density = LocalDensity.current
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp, max = 440.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .transformable(transformState)
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val bmpWidth = bitmap.width.coerceAtLeast(1)
                    val bmpHeight = bitmap.height.coerceAtLeast(1)
                    val imageAspect = bmpWidth.toFloat() / bmpHeight.toFloat()
                    val boxAspect = if (maxHeight.value > 0f) maxWidth / maxHeight else 1f
                    val fittedWidth: Dp
                    val fittedHeight: Dp
                    if (imageAspect > boxAspect) {
                        fittedWidth = maxWidth
                        fittedHeight = maxWidth / imageAspect
                    } else {
                        fittedHeight = maxHeight
                        fittedWidth = maxHeight * imageAspect
                    }
                    // Bitmap px -> screen px scale for a ContentScale.Fit layout.
                    val s = with(density) { fittedWidth.toPx() } / bmpWidth
                    val outlineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)

                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(fittedWidth, fittedHeight)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                                rotationZ = rotation
                            }
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.Fit
                        )

                        // Region outlines (original side of the comparison).
                        if (state.comparing != CompareMode.TRANSLATED) {
                            Canvas(Modifier.matchParentSize()) {
                                state.ocr?.regions?.forEach { region ->
                                    val rect = region.boundingBox ?: return@forEach
                                    drawRect(
                                        color = outlineColor,
                                        topLeft = Offset(rect.left * s, rect.top * s),
                                        size = Size(rect.width() * s, rect.height() * s),
                                        style = Stroke(width = 2.dp.toPx())
                                    )
                                }
                            }
                        }

                        // Tap-to-translate hit areas over each region.
                        state.ocr?.regions?.forEachIndexed { index, region ->
                            val rect = region.boundingBox ?: return@forEachIndexed
                            Box(
                                Modifier
                                    .offset {
                                        IntOffset(
                                            (rect.left * s).roundToInt(),
                                            (rect.top * s).roundToInt()
                                        )
                                    }
                                    .size(
                                        with(density) { (rect.width() * s).toDp() },
                                        with(density) { (rect.height() * s).toDp() }
                                    )
                                    .clickable { vm.translateRegion(index) }
                            )
                        }

                        // Translated overlays rendered on top of the regions.
                        if (state.comparing == CompareMode.TRANSLATED) {
                            state.ocr?.regions?.forEachIndexed { index, region ->
                                val rect = region.boundingBox ?: return@forEachIndexed
                                val text = state.translatedRegions.getOrNull(index).orEmpty()
                                if (text.isBlank()) return@forEachIndexed
                                Text(
                                    text = text,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 11.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .offset {
                                            IntOffset(
                                                (rect.left * s).roundToInt(),
                                                (rect.top * s).roundToInt()
                                            )
                                        }
                                        .size(
                                            with(density) { (rect.width() * s).toDp() },
                                            with(density) { (rect.height() * s).toDp() }
                                        )
                                        .background(
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                                        )
                                        .clickable { vm.translateRegion(index) }
                                        .padding(2.dp)
                                )
                            }
                        }
                    }
                }
            }

            // --- Viewer controls ---
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = { rotation += 90f }) {
                    Icon(Icons.Filled.RotateRight, contentDescription = "Rotate image")
                }
                IconButton(
                    onClick = {
                        scale = 1f
                        offset = Offset.Zero
                        rotation = 0f
                    }
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = "Reset view")
                }
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    CompareMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.comparing == mode,
                            onClick = { vm.setCompare(mode) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = CompareMode.entries.size
                            ),
                            label = {
                                Text(
                                    mode.name.lowercase().replaceFirstChar { it.uppercase() }
                                )
                            }
                        )
                    }
                }
            }

            // --- Actions ---
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { vm.translateAll() },
                    enabled = state.ocr != null && !state.translating,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Translate All")
                }
                if (state.translating) {
                    OutlinedButton(onClick = { vm.cancelTranslation() }) {
                        Text("Cancel")
                    }
                }
            }
            if (state.translating) {
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }

            // --- Full translation result ---
            state.translatedFull?.let { full ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Translation",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { AndroidUtils.copyToClipboard(context, full) }
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy translation")
                        }
                        IconButton(
                            onClick = { AndroidUtils.shareText(context, full) }
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = "Share translation")
                        }
                    }
                    SelectionContainer {
                        Text(full, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // --- Split comparison below the image ---
            if (state.comparing == CompareMode.SPLIT) {
                state.ocr?.let { ocr ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "Original",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            ocr.regions.forEach { region ->
                                Text(
                                    region.text,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "Translated",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            ocr.regions.forEachIndexed { index, _ ->
                                val translated = state.translatedRegions
                                    .getOrNull(index)
                                    .orEmpty()
                                    .ifBlank { "—" }
                                Text(
                                    translated,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
        LoadingOverlay(visible = state.translating, label = "Translating…")
    }
}
