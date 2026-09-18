package com.axis.translate.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Queue
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.axis.translate.domain.model.TranslationResult
import com.axis.translate.ui.components.ErrorBanner
import com.axis.translate.ui.components.LanguageBar
import com.axis.translate.ui.components.LoadingOverlay
import com.axis.translate.ui.components.OfflineBadge
import com.axis.translate.ui.navigation.AppNavigator
import com.axis.translate.ui.navigation.PendingInput
import com.axis.translate.ui.navigation.Routes
import com.axis.translate.util.AndroidUtils
import com.axis.translate.util.rememberContainer
import kotlinx.coroutines.launch

/**
 * Home screen: hero translation card with language selector, text input with
 * paste/voice/camera shortcuts, a prominent translate action with inline
 * loading, and the result card with speak/copy/share/favorite actions.
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val container = rememberContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val state by vm.uiState.collectAsStateWithLifecycle()

    var showSourcePicker by remember { mutableStateOf(false) }
    var showTargetPicker by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    fun showSnackbar(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = container.speechRecognizer.extractResult(result.resultCode, result.data)
        if (!text.isNullOrBlank()) {
            vm.onInputChange(text)
        }
    }

    // An image shared into the app (share-target) is routed to the photo flow.
    LaunchedEffect(Unit) {
        PendingInput.sharedImageUri.collect { uri ->
            if (uri != null) {
                AppNavigator.navigate(AppNavigator.PHOTO_ROUTE)
            }
        }
    }

    fun launchVoiceInput() {
        if (!container.speechRecognizer.isOfflineAvailable(context)) {
            showSnackbar("Offline voice recognition is not available on this device.")
        } else {
            val languageHint = state.source.takeIf { !it.isAuto }?.code
            speechLauncher.launch(container.speechRecognizer.createRecognizeIntent(languageHint))
        }
    }

    fun pasteFromClipboard() {
        val clipboard = AndroidUtils.clipboardText(context)
        if (clipboard.isNullOrBlank()) {
            showSnackbar("Clipboard is empty.")
        } else {
            vm.paste(clipboard)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Axis Translate",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
                OfflineBadge()
            }

            if (state.showModelBanner) {
                ModelInstallBanner()
            }

            // Hero translation card: language pair, input, and the primary action.
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LanguageBar(
                        source = state.source,
                        target = state.target,
                        onSourceClick = { showSourcePicker = true },
                        onTargetClick = { showTargetPicker = true },
                        onSwap = vm::swap
                    )

                    OutlinedTextField(
                        value = state.input,
                        onValueChange = vm::onInputChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("home_input"),
                        placeholder = { Text(text = "Type or paste text…") },
                        minLines = 5,
                        shape = MaterialTheme.shapes.large
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { pasteFromClipboard() }) {
                            Icon(
                                imageVector = Icons.Outlined.ContentPaste,
                                contentDescription = "Paste from clipboard"
                            )
                        }
                        IconButton(onClick = { launchVoiceInput() }) {
                            Icon(
                                imageVector = Icons.Outlined.Mic,
                                contentDescription = "Voice input"
                            )
                        }
                        IconButton(onClick = { AppNavigator.navigate(Routes.CAMERA) }) {
                            Icon(
                                imageVector = Icons.Outlined.PhotoCamera,
                                contentDescription = "Translate with camera"
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (state.input.isNotEmpty()) {
                            IconButton(onClick = { vm.onInputChange("") }) {
                                Icon(
                                    imageVector = Icons.Outlined.Clear,
                                    contentDescription = "Clear input"
                                )
                            }
                        }
                    }

                    Button(
                        onClick = { if (state.translating) vm.cancel() else vm.translate() },
                        enabled = state.input.isNotBlank() || state.translating,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .testTag("home_translate"),
                        shape = MaterialTheme.shapes.large,
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        if (state.translating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(text = "Translating…", style = MaterialTheme.typography.titleSmall)
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Translate,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(text = "Translate", style = MaterialTheme.typography.titleSmall)
                        }
                    }

                    if (state.translating) {
                        // Streaming progress; tapping the action again cancels.
                        if (state.progress != null) {
                            LinearProgressIndicator(
                                progress = { state.progress ?: 0f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        state.partial?.takeIf { it.isNotBlank() }?.let { partial ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Text(
                                    text = partial,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }
            }

            state.result?.let { result ->
                TranslationResultCard(
                    result = result,
                    detectedLabel = state.detectedLabel,
                    onSpeak = {
                        container.textSpeaker.speak(result.translatedText, state.target.code)
                    },
                    onCopy = {
                        AndroidUtils.copyToClipboard(context, result.translatedText)
                        showSnackbar("Copied to clipboard.")
                    },
                    onShare = {
                        AndroidUtils.shareText(context, result.translatedText)
                    },
                    onFavorite = {
                        vm.saveFavorite()
                        showSnackbar("Saved to favorites.")
                    }
                )
            }

            state.error?.let { error ->
                ErrorBanner(message = error, onDismiss = vm::dismissError)
            }

            // Quick actions to secondary destinations that are not on the bottom bar.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickAction(
                    icon = Icons.Outlined.Forum,
                    label = "Conversation",
                    modifier = Modifier.weight(1f)
                ) { AppNavigator.navigate(Routes.CONVERSATION) }
                QuickAction(
                    icon = Icons.Outlined.Description,
                    label = "Documents",
                    modifier = Modifier.weight(1f)
                ) { AppNavigator.navigate(Routes.DOCUMENTS) }
                QuickAction(
                    icon = Icons.Outlined.Queue,
                    label = "Batch",
                    modifier = Modifier.weight(1f)
                ) { AppNavigator.navigate(Routes.BATCH) }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        if (showSourcePicker) {
            LanguageSelectionSheet(
                isTarget = false,
                languages = state.languages,
                recents = state.recentPairs,
                favorites = state.favoriteLanguages,
                onSelect = { language ->
                    vm.setSource(language)
                    showSourcePicker = false
                },
                onDismiss = { showSourcePicker = false },
                onToggleFavorite = vm::toggleFavoriteLanguage,
                onRecentPairSelected = { pair ->
                    vm.applyRecentPair(pair)
                    showSourcePicker = false
                }
            )
        }

        if (showTargetPicker) {
            LanguageSelectionSheet(
                isTarget = true,
                languages = state.languages,
                recents = state.recentPairs,
                favorites = state.favoriteLanguages,
                onSelect = { language ->
                    vm.setTarget(language)
                    showTargetPicker = false
                },
                onDismiss = { showTargetPicker = false },
                onToggleFavorite = vm::toggleFavoriteLanguage,
                onRecentPairSelected = { pair ->
                    vm.applyRecentPair(pair)
                    showTargetPicker = false
                }
            )
        }

        // Modal scrim while the engine is still loading (no streamed output yet);
        // once progress/partial text arrives, the inline progress takes over.
        LoadingOverlay(
            visible = state.translating && state.progress == null && state.partial == null,
            label = "Translating…"
        )
    }
}

/** First-run hint when no translation model is installed yet. */
@Composable
private fun ModelInstallBanner() {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(10.dp)
                            .size(22.dp)
                    )
                }
                Column {
                    Text(
                        text = "AI model is not installed.",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Download the offline translation model to get started.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Button(
                onClick = { AppNavigator.navigate(Routes.MODEL) },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large
            ) {
                Text(text = "Install Model")
            }
        }
    }
}

/** Result surface: distinct secondary container with tonal action circles. */
@Composable
private fun TranslationResultCard(
    result: TranslationResult,
    detectedLabel: String?,
    onSpeak: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Translation",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                detectedLabel?.let { label ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Text(
                            text = "Detected: $label",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            SelectionContainer {
                Text(
                    text = result.translatedText,
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ResultAction(
                    icon = Icons.Outlined.VolumeUp,
                    description = "Speak translation",
                    onClick = onSpeak
                )
                ResultAction(
                    icon = Icons.Outlined.ContentCopy,
                    description = "Copy translation",
                    onClick = onCopy
                )
                ResultAction(
                    icon = Icons.Outlined.Share,
                    description = "Share translation",
                    onClick = onShare
                )
                ResultAction(
                    icon = Icons.Outlined.StarBorder,
                    description = "Save to favorites",
                    onClick = onFavorite
                )
            }
        }
    }
}

/** Circular tonal action used inside the result card. */
@Composable
private fun ResultAction(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier
                .padding(12.dp)
                .size(20.dp)
        )
    }
}

/** Compact tonal tile linking to a secondary destination. */
@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
