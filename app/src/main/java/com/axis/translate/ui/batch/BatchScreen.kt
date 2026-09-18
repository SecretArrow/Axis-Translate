package com.axis.translate.ui.batch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Queue
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.axis.translate.domain.BatchQueue
import com.axis.translate.domain.model.BatchState
import com.axis.translate.ui.components.ConfirmDialog
import com.axis.translate.ui.components.EmptyState
import com.axis.translate.util.AndroidUtils
import com.axis.translate.util.rememberContainer

/**
 * Batch screen: compose a queue of text tasks, run them through the
 * foreground batch service, and review/copy/retry each result.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BatchScreen(modifier: Modifier = Modifier) {
    val container = rememberContainer()
    val vm: BatchViewModel = viewModel(factory = BatchViewModel.factory(container))
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var input by remember { mutableStateOf("") }
    var showCancelDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Compose section: free text goes straight onto the queue.
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Text to translate") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                vm.addTextTask(input)
                input = ""
            },
            enabled = input.isNotBlank()
        ) {
            Text("Add to queue")
        }

        val items = state.items
        if (items.isNotEmpty()) {
            val done = items.count { it.state == BatchState.DONE }
            Text(
                text = "Processing $done / ${items.size}",
                style = MaterialTheme.typography.labelLarge
            )
            LinearProgressIndicator(
                progress = { if (items.isEmpty()) 0f else done.toFloat() / items.size },
                modifier = Modifier.fillMaxWidth()
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = { vm.start(context) },
                // The runner only processes PENDING items; FAILED items need an
                // explicit "Retry failed" first, so gate the button on PENDING.
                enabled = !state.running &&
                    items.any { it.state == BatchState.PENDING }
            ) {
                Text("Translate All")
            }
            OutlinedButton(
                onClick = { if (state.paused) vm.resume() else vm.pause() },
                enabled = state.running
            ) {
                Text(if (state.paused) "Resume" else "Pause")
            }
            OutlinedButton(
                onClick = { showCancelDialog = true },
                enabled = state.running
            ) {
                Text("Cancel")
            }
            TextButton(
                onClick = vm::clear,
                enabled = !state.running
            ) {
                Text("Clear")
            }
            if (items.any { it.state == BatchState.FAILED } && !state.running) {
                TextButton(onClick = vm::retryFailed) {
                    Text("Retry failed")
                }
            }
        }

        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    icon = Icons.Outlined.Queue,
                    title = "Batch queue is empty",
                    subtitle = "Add text to translate in bulk"
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.task.id }) { taskState ->
                    BatchTaskItem(
                        taskState = taskState,
                        onCopy = {
                            AndroidUtils.copyToClipboard(
                                context,
                                taskState.result ?: ""
                            )
                        },
                        onRetry = { vm.retry(taskState.task.id) },
                        onRemove = { vm.remove(taskState.task.id) }
                    )
                }
            }
        }
    }

    if (showCancelDialog) {
        ConfirmDialog(
            title = "Cancel batch?",
            message = "Pending items will stop.",
            confirmLabel = "Cancel batch",
            onConfirm = {
                showCancelDialog = false
                vm.cancelAll()
            },
            onDismiss = { showCancelDialog = false }
        )
    }
}

/** One queue entry: label, language pair, state chip and per-state actions. */
@Composable
private fun BatchTaskItem(taskState: BatchQueue.TaskState, onCopy: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val task = taskState.task
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.label,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "${task.source.displayName} → ${task.target.displayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StateChip(state = taskState.state)
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Remove from queue"
                    )
                }
            }

            when (taskState.state) {
                BatchState.DONE -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = taskState.result.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onCopy) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = "Copy result"
                            )
                        }
                    }
                }
                BatchState.FAILED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = taskState.error ?: "Translation failed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onRetry) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Retry task"
                            )
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

/** Compact state badge with a color per [BatchState]. */
@Composable
private fun StateChip(state: BatchState, modifier: Modifier = Modifier) {
    val (label, containerColor, contentColor) = when (state) {
        BatchState.PENDING -> Triple(
            "Pending",
            MaterialTheme.colorScheme.outlineVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        BatchState.RUNNING -> Triple(
            "Running",
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onPrimary
        )
        BatchState.DONE -> Triple(
            "Done",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        BatchState.FAILED -> Triple(
            "Failed",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
        BatchState.CANCELLED -> Triple(
            "Cancelled",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
