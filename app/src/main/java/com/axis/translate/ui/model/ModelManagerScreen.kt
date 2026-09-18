package com.axis.translate.ui.model

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.axis.translate.domain.model.ModelManifestEntry
import com.axis.translate.domain.model.ModelProgress
import com.axis.translate.domain.model.ModelStatus
import com.axis.translate.ui.components.ConfirmDialog
import com.axis.translate.ui.components.ErrorBanner
import com.axis.translate.ui.components.LoadingOverlay
import com.axis.translate.ui.components.OfflineBadge
import com.axis.translate.ui.components.SectionHeader
import com.axis.translate.util.AndroidUtils
import com.axis.translate.util.rememberContainer

/** Accent for the "installed / ready" status visuals. */
private val StatusGreen = Color(0xFF2E7D32)

/**
 * AI model manager (SPEC #35–#38): installed-model summary, download list
 * with live progress, manual SAF import, verification, and reload.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelManagerScreen(modifier: Modifier = Modifier) {
    val container = rememberContainer()
    val vm: ModelManagerViewModel = viewModel(factory = ModelManagerViewModel.factory(container))
    val state by vm.ui.collectAsStateWithLifecycle()

    var showRemoveConfirm by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(vm::importModel)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --------------------------------------------------------------
            // Header
            // --------------------------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AI Model",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
                OfflineBadge()
            }

            // --------------------------------------------------------------
            // Errors
            // --------------------------------------------------------------
            state.error?.let { error ->
                ErrorBanner(message = error, onDismiss = vm::dismissError)
            }

            // --------------------------------------------------------------
            // Installed model summary
            // --------------------------------------------------------------
            state.installed?.let { installed ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = installed.entry.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            AssistChip(
                                onClick = {},
                                label = { Text(installed.entry.quantization) }
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = StatusGreen
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Status: Ready",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            text = "Storage: ${AndroidUtils.formatBytes(installed.sizeBytes)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Runtime: ${installed.entry.runtime}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Context: ${installed.entry.contextLength}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(onClick = vm::verify) {
                                Text("Verify")
                            }
                            OutlinedButton(onClick = vm::reload) {
                                Text("Reload Model")
                            }
                            OutlinedButton(onClick = { showRemoveConfirm = true }) {
                                Text("Remove Model")
                            }
                        }
                    }
                }
            }

            // --------------------------------------------------------------
            // Verification result
            // --------------------------------------------------------------
            when (state.verifyResult) {
                true -> Text(
                    text = "✓ Verified",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                false -> Text(
                    text = "SHA-256 mismatch",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                else -> Unit
            }

            // --------------------------------------------------------------
            // Available models
            // --------------------------------------------------------------
            SectionHeader("Available models")
            state.entries.forEach { entry ->
                ModelEntryCard(
                    entry = entry,
                    installedId = state.installed?.entry?.id,
                    busy = state.busy,
                    progress = state.progress,
                    onInstall = { vm.install(entry) }
                )
            }

            // --------------------------------------------------------------
            // Manual import
            // --------------------------------------------------------------
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Outlined.UploadFile,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text("Import Model")
            }

            Text(
                text = "Network is used only for model download. Inference is fully offline.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        // Block interaction only for short, non-download phases (verify,
        // install finalization, engine reload) — never during the download
        // itself, which shows inline progress instead.
        LoadingOverlay(
            visible = state.reloading ||
                state.progress?.status == ModelStatus.VERIFYING ||
                state.progress?.status == ModelStatus.INSTALLING,
            label = "Preparing model…"
        )
    }

    if (showRemoveConfirm) {
        ConfirmDialog(
            title = "Remove AI model?",
            message = "You can re-download it later.",
            confirmLabel = "Remove",
            onConfirm = {
                vm.remove()
                showRemoveConfirm = false
            },
            onDismiss = { showRemoveConfirm = false }
        )
    }
}

/** One downloadable model from the manifest, with install state + progress. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelEntryCard(entry: ModelManifestEntry, installedId: String?, busy: Boolean, progress: ModelProgress?, onInstall: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.titleMedium
            )
            if (entry.description.isNotBlank()) {
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(entry.quantization) })
                AssistChip(
                    onClick = {},
                    label = { Text(AndroidUtils.formatBytes(entry.sizeBytes)) }
                )
                AssistChip(onClick = {}, label = { Text("${entry.contextLength} ctx") })
                if (entry.license.isNotBlank()) {
                    AssistChip(onClick = {}, label = { Text(entry.license) })
                }
            }

            if (installedId == entry.id) {
                AssistChip(
                    onClick = {},
                    label = { Text("Installed") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            } else {
                Button(onClick = onInstall, enabled = !busy) {
                    Text("Install Model")
                }
            }

            if (progress?.entryId == entry.id && progress.status == ModelStatus.DOWNLOADING) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { progress.percent },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Downloading… ${(progress.percent * 100).toInt()}% " +
                            "(${AndroidUtils.formatBytes(progress.bytesDownloaded)} / " +
                            "${AndroidUtils.formatBytes(progress.totalBytes)})",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}
