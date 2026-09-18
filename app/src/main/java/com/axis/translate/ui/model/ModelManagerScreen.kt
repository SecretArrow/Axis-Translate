package com.axis.translate.ui.model

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.axis.translate.domain.model.InstalledModelInfo
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
import java.io.File

/** Accent for the "installed / ready" status visuals. */
private val StatusGreen = Color(0xFF2E7D32)

/** Fallback name when the SAF export launcher runs without an installed model entry. */
private const val DEFAULT_EXPORT_FILE_NAME = "model.gguf"

/**
 * AI model manager (SPEC #35–#38): installed-model summary, download list
 * with live progress, manual SAF import/export, verification, sharing, and
 * engine reload.
 */
@Composable
fun ModelManagerScreen(modifier: Modifier = Modifier) {
    val container = rememberContainer()
    val vm: ModelManagerViewModel = viewModel(factory = ModelManagerViewModel.factory(container))
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showRemoveConfirm by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(vm::importModel)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let(vm::exportModel)
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
                Icon(
                    imageVector = Icons.Outlined.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "AI Model",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                OfflineBadge()
            }

            // --------------------------------------------------------------
            // Errors
            // --------------------------------------------------------------
            // Cache the last non-null message so the exit animation still
            // has a banner to animate out once the error is cleared.
            var lastError by remember { mutableStateOf<String?>(null) }
            state.error?.let { error -> lastError = error }
            AnimatedVisibility(
                visible = state.error != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                lastError?.let { error ->
                    ErrorBanner(message = error, onDismiss = vm::dismissError)
                }
            }

            // --------------------------------------------------------------
            // Installed model summary
            // --------------------------------------------------------------
            state.installed?.let { installed ->
                InstalledModelCard(
                    installed = installed,
                    busy = state.busy,
                    exporting = state.exporting,
                    verifyResult = state.verifyResult,
                    onVerify = vm::verify,
                    onReload = vm::reload,
                    onExport = { exportLauncher.launch(vm.exportFileName() ?: DEFAULT_EXPORT_FILE_NAME) },
                    onShare = {
                        val shared = runCatching {
                            val shareUri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                File(installed.path)
                            )
                            AndroidUtils.shareFile(context, shareUri, "application/octet-stream", "Share model")
                        }.isSuccess
                        if (!shared) {
                            Toast.makeText(context, "Could not share the model file", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onRemove = { showRemoveConfirm = true }
                )
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
            FilledTonalButton(
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
        // itself, which shows inline progress instead. Exports stay
        // interactive and surface their own inline progress.
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

/** Summary card for the single installed model, with manage actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstalledModelCard(
    installed: InstalledModelInfo,
    busy: Boolean,
    exporting: Boolean,
    verifyResult: Boolean?,
    onVerify: () -> Unit,
    onReload: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onRemove: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = installed.entry.displayName,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Installed on ${AndroidUtils.formatDate(installed.installedAt)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                InfoBadge(text = installed.entry.quantization)
            }

            // Tonal status row.
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = StatusGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Status: Ready — model installed",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            DetailRow(label = "Storage", value = AndroidUtils.formatBytes(installed.sizeBytes))
            DetailRow(label = "Runtime", value = installed.entry.runtime)
            DetailRow(label = "Context", value = "${installed.entry.contextLength} tokens")

            // Verification feedback.
            verifyResult?.let { ok ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = if (ok) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    contentColor = if (ok) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                ) {
                    Text(
                        text = if (ok) {
                            "Verified — checksum matches the expected SHA-256."
                        } else {
                            "SHA-256 mismatch — the model file may be corrupted."
                        },
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // Manage actions.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onVerify, enabled = !busy && !exporting) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Verify")
                }
                OutlinedButton(onClick = onReload, enabled = !busy && !exporting) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Reload Model")
                }
                OutlinedButton(onClick = onRemove, enabled = !busy && !exporting) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Remove Model")
                }
            }

            // Export / share actions.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onExport,
                    enabled = !busy && !exporting,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = if (exporting) "Exporting…" else "Export Model")
                }
                OutlinedButton(onClick = onShare, enabled = !busy && !exporting) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
            }

            if (exporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** One downloadable model from the manifest, with install state + progress. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelEntryCard(
    entry: ModelManifestEntry,
    installedId: String?,
    busy: Boolean,
    progress: ModelProgress?,
    onInstall: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large
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
                    text = entry.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (installedId == entry.id) {
                    InfoBadge(text = "Installed", leadingIcon = Icons.Outlined.Check)
                }
            }
            if (entry.description.isNotBlank()) {
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoBadge(text = entry.quantization)
                InfoBadge(text = AndroidUtils.formatBytes(entry.sizeBytes))
                InfoBadge(text = "${entry.contextLength} ctx")
                if (entry.license.isNotBlank()) {
                    InfoBadge(text = entry.license)
                }
            }

            if (installedId != entry.id) {
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

/** Compact tonal metadata badge replacing the former AssistChip rows. */
@Composable
private fun InfoBadge(text: String, leadingIcon: ImageVector? = null, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

/** "Label ——— value" line for the installed model detail list. */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
}
