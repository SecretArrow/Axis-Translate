package com.axis.translate.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.axis.translate.data.settings.ThemeMode
import com.axis.translate.domain.model.TranslationStyle
import com.axis.translate.service.FloatingTranslateService
import com.axis.translate.ui.components.ConfirmDialog
import com.axis.translate.ui.components.OfflineBadge
import com.axis.translate.ui.components.SectionHeader
import com.axis.translate.ui.components.StatChip
import com.axis.translate.ui.navigation.AppNavigator
import com.axis.translate.ui.navigation.Routes
import com.axis.translate.util.rememberContainer

/** Context-length presets offered in the AI Engine section. */
private val CONTEXT_LENGTH_OPTIONS = listOf(1024, 2048, 4096)

/**
 * Settings screen (SPEC #49): appearance, translation behaviour, engine
 * tuning, camera, data management, floating translation (with overlay
 * permission flow), privacy, developer diagnostics, and About.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val container = rememberContainer()
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Destructured once for readability.
    val settings = state.settings
    val runtimeInfo = state.runtimeInfo

    // Local slider state: commit to DataStore only when the drag finishes
    // so we don't spam persistence on every frame.
    var threads by remember(settings.inferenceThreads) {
        mutableStateOf(settings.inferenceThreads.toFloat())
    }
    var styleExpanded by remember { mutableStateOf(false) }
    var contextExpanded by remember { mutableStateOf(false) }
    var showClearHistory by remember { mutableStateOf(false) }
    var showClearFavorites by remember { mutableStateOf(false) }

    // App version resolved lazily from PackageManager.
    var versionName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ------------------------------------------------------------------
        // Appearance
        // ------------------------------------------------------------------
        SectionHeader("Appearance")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val themes = listOf(
                ThemeMode.SYSTEM to "System",
                ThemeMode.LIGHT to "Light",
                ThemeMode.DARK to "Dark"
            )
            themes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = settings.themeMode == mode,
                    onClick = { vm.setTheme(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = themes.size)
                ) {
                    Text(label)
                }
            }
        }

        // ------------------------------------------------------------------
        // Translation
        // ------------------------------------------------------------------
        SectionHeader("Translation")
        ExposedDropdownMenuBox(
            expanded = styleExpanded,
            onExpandedChange = { styleExpanded = it }
        ) {
            val currentStyleLabel = TranslationStyle.entries
                .firstOrNull { it.name == settings.translationStyle }?.label
                ?: settings.translationStyle
            OutlinedTextField(
                value = currentStyleLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text("Translation style") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = styleExpanded)
                },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = styleExpanded,
                onDismissRequest = { styleExpanded = false }
            ) {
                TranslationStyle.entries.forEach { style ->
                    DropdownMenuItem(
                        text = { Text(style.label) },
                        onClick = {
                            vm.setStyle(style.name)
                            styleExpanded = false
                        }
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Auto-detect language",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = settings.autoDetectLanguage,
                onCheckedChange = vm::setAutoDetect
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Glossary",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = settings.glossaryEnabled,
                onCheckedChange = vm::setGlossary
            )
        }
        TextButton(onClick = { AppNavigator.navigate(Routes.GLOSSARY) }) {
            Text("Manage glossary")
        }

        // ------------------------------------------------------------------
        // AI Engine
        // ------------------------------------------------------------------
        SectionHeader("AI Engine")
        Column {
            Text(
                text = "Threads: ${threads.toInt()}",
                style = MaterialTheme.typography.bodyLarge
            )
            Slider(
                value = threads,
                onValueChange = { threads = it },
                onValueChangeFinished = { vm.setThreads(threads.toInt()) },
                valueRange = 1f..8f,
                // 6 notches between 1 and 8 → snaps to whole thread counts.
                steps = 6
            )
        }
        ExposedDropdownMenuBox(
            expanded = contextExpanded,
            onExpandedChange = { contextExpanded = it }
        ) {
            OutlinedTextField(
                value = settings.contextLength.toString(),
                onValueChange = {},
                readOnly = true,
                label = { Text("Context length") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = contextExpanded)
                },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = contextExpanded,
                onDismissRequest = { contextExpanded = false }
            ) {
                CONTEXT_LENGTH_OPTIONS.forEach { length ->
                    DropdownMenuItem(
                        text = { Text(length.toString()) },
                        onClick = {
                            vm.setContextLength(length)
                            contextExpanded = false
                        }
                    )
                }
            }
        }
        TextButton(onClick = { AppNavigator.navigate(Routes.MODEL) }) {
            Text("AI model manager")
        }
        TextButton(onClick = { AppNavigator.navigate(Routes.BATCH) }) {
            Text("Batch queue")
        }

        // ------------------------------------------------------------------
        // Camera
        // ------------------------------------------------------------------
        SectionHeader("Camera")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Live camera translation",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = settings.liveCameraTranslation,
                onCheckedChange = vm::setLiveCamera
            )
        }

        // ------------------------------------------------------------------
        // Data
        // ------------------------------------------------------------------
        SectionHeader("Data")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Save translated photos",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = settings.saveTranslatedPhotos,
                onCheckedChange = vm::setSavePhotos
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { showClearHistory = true }) {
                Text("Clear history")
            }
            OutlinedButton(onClick = { showClearFavorites = true }) {
                Text("Clear favorites")
            }
        }

        // ------------------------------------------------------------------
        // Floating translation (overlay permission aware)
        // ------------------------------------------------------------------
        SectionHeader("Floating")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Floating translation",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = settings.floatingTranslationEnabled,
                onCheckedChange = { enabled ->
                    if (enabled && !Settings.canDrawOverlays(context)) {
                        // Send the user to the system overlay-permission screen.
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + context.packageName)
                            )
                        )
                    }
                    vm.setFloating(enabled)
                    if (enabled && Settings.canDrawOverlays(context)) {
                        FloatingTranslateService.start(context)
                    } else if (!enabled) {
                        FloatingTranslateService.stop(context)
                    }
                }
            )
        }

        // ------------------------------------------------------------------
        // Privacy
        // ------------------------------------------------------------------
        SectionHeader("Privacy")
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "All translation, OCR and speech run on this device. " +
                        "Network is used only to download AI models.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                OfflineBadge()
            }
        }

        // ------------------------------------------------------------------
        // Developer
        // ------------------------------------------------------------------
        SectionHeader("Developer")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Developer mode",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = settings.developerMode,
                onCheckedChange = vm::setDeveloper
            )
        }
        if (settings.developerMode) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip(label = "Runtime", value = "llama.cpp")
                StatChip(label = "Threads", value = runtimeInfo?.threads?.toString() ?: "-")
                StatChip(label = "Context", value = runtimeInfo?.contextLength?.toString() ?: "-")
            }
            Spacer(Modifier.height(8.dp))
            StatChip(label = "Version", value = runtimeInfo?.version ?: "-")
        }

        // ------------------------------------------------------------------
        // About
        // ------------------------------------------------------------------
        SectionHeader("About")
        Text(
            text = "Axis Translate $versionName",
            style = MaterialTheme.typography.bodyMedium
        )
    }

    // Destructive-action confirmations.
    if (showClearHistory) {
        ConfirmDialog(
            title = "Clear history?",
            message = "All previous translations will be removed. This cannot be undone.",
            confirmLabel = "Clear",
            onConfirm = {
                vm.clearHistory()
                showClearHistory = false
            },
            onDismiss = { showClearHistory = false }
        )
    }
    if (showClearFavorites) {
        ConfirmDialog(
            title = "Clear favorites?",
            message = "All favorite translations will be removed. This cannot be undone.",
            confirmLabel = "Clear",
            onConfirm = {
                vm.clearFavorites()
                showClearFavorites = false
            },
            onDismiss = { showClearFavorites = false }
        )
    }
}
