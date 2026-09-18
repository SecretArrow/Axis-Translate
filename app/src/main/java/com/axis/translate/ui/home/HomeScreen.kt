package com.axis.translate.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Queue
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectionContainer
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
 * Home screen: language bar, text input with paste/voice/camera shortcuts,
 * streaming translation with cancel, and the result card with
 * speak/copy/share/favorite actions.
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "AI model is not installed.",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Download the offline translation model to get started.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = { AppNavigator.navigate(Routes.MODEL) }) {
                            Text(text = "Install Model")
                        }
                    }
                }
            }

            LanguageBar(
                source = state.source,
                target = state.target,
                onSourceClick = { showSourcePicker = true },
                onTargetClick = { showTargetPicker = true },
                onSwap = vm::swap
            )

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = vm::onInputChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("home_input"),
                        placeholder = { Text(text = "Type or paste text…") },
                        minLines = 4
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
                }
            }

            if (state.translating) {
                if (state.progress != null) {
                    LinearProgressIndicator(
                        progress = { state.progress ?: 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                OutlinedButton(
                    onClick = vm::cancel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Cancel")
                }
                state.partial?.takeIf { it.isNotBlank() }?.let { partial ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = partial,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            } else {
                Button(
                    onClick = vm::translate,
                    enabled = state.input.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_translate")
                ) {
                    Text(text = "Translate")
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
        // once progress/partial text arrives, the inline progress + Cancel take over.
        LoadingOverlay(
            visible = state.translating && state.progress == null && state.partial == null,
            label = "Translating…"
        )
    }
}

@Composable
private fun TranslationResultCard(
    result: TranslationResult,
    detectedLabel: String?,
    onSpeak: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Translation",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                detectedLabel?.let { label ->
                    Text(
                        text = "Detected: $label",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            SelectionContainer {
                Text(
                    text = result.translatedText,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onSpeak) {
                    Icon(
                        imageVector = Icons.Outlined.VolumeUp,
                        contentDescription = "Speak translation"
                    )
                }
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy translation"
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = "Share translation"
                    )
                }
                IconButton(onClick = onFavorite) {
                    Icon(
                        imageVector = Icons.Outlined.StarBorder,
                        contentDescription = "Save to favorites"
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedCard(
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
