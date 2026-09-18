package com.axis.translate.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Input
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.axis.translate.domain.model.HistoryItem
import com.axis.translate.domain.model.InputType
import com.axis.translate.ui.components.ConfirmDialog
import com.axis.translate.ui.components.EmptyState
import com.axis.translate.ui.navigation.AppNavigator
import com.axis.translate.ui.navigation.PendingInput
import com.axis.translate.ui.navigation.Routes
import com.axis.translate.util.AndroidUtils
import com.axis.translate.util.rememberContainer

/**
 * History screen: searchable, filterable list of past translations. Swipe a
 * card towards the end to delete; star a card to favorite; overflow menu
 * offers copy / share / use-as-input.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val container = rememberContainer()
    val vm: HistoryViewModel = viewModel(factory = HistoryViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showClearDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "History",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (state.items.isNotEmpty()) {
                IconButton(onClick = { showClearDialog = true }) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear history")
                }
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = vm::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("Search history…") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.filter == null,
                onClick = { vm.setFilter(null) },
                label = { Text("All") },
            )
            InputType.entries.forEach { type ->
                FilterChip(
                    selected = state.filter == type,
                    onClick = { vm.setFilter(if (state.filter == type) null else type) },
                    label = { Text(type.displayLabel()) },
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                state.items.isEmpty() && state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                state.items.isEmpty() -> {
                    val filtered = state.query.isNotBlank() || state.filter != null
                    EmptyState(
                        icon = Icons.Outlined.History,
                        title = if (filtered) "No matching translations" else "No translations yet",
                        subtitle = if (filtered) "Try a different search or filter." else "Start translating!",
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        HistoryItemCard(
                            item = item,
                            onToggleFavorite = { vm.toggleFavorite(item) },
                            onDelete = { vm.delete(item) },
                            onCopy = { AndroidUtils.copyToClipboard(context, item.translatedText) },
                            onShare = { AndroidUtils.shareText(context, item.translatedText) },
                            onUseAsInput = {
                                PendingInput.sharedText.value = item.sourceText
                                AppNavigator.navigate(Routes.HOME)
                            },
                        )
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        ConfirmDialog(
            title = "Clear history?",
            message = "All translation history will be permanently deleted.",
            confirmLabel = "Clear",
            onConfirm = {
                vm.clearAll()
                showClearDialog = false
            },
            onDismiss = { showClearDialog = false },
        )
    }
}

/** A single history entry with swipe-to-delete, favorite toggle and overflow actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryItemCard(
    item: HistoryItem,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onUseAsInput: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )
    var menuOpen by remember { mutableStateOf(false) }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = languageLine(item.sourceCode, item.targetCode),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = item.sourceText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.translatedText,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${item.inputType.displayLabel()} • ${AndroidUtils.formatDate(item.timestamp)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (item.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (item.isFavorite) {
                            "Remove from favorites"
                        } else {
                            "Add to favorites"
                        },
                        tint = if (item.isFavorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Copy") },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onCopy()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Share") },
                            leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onShare()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Use as input") },
                            leadingIcon = { Icon(Icons.Outlined.Input, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onUseAsInput()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Human-readable chip/label text for an [InputType]. */
private fun InputType.displayLabel(): String = when (this) {
    InputType.TEXT -> "Text"
    InputType.PHOTO -> "Photo"
    InputType.OCR -> "OCR"
    InputType.VOICE -> "Voice"
    InputType.DOCUMENT -> "Document"
    InputType.CLIPBOARD -> "Clipboard"
    InputType.SHARE -> "Share"
    InputType.CONVERSATION -> "Conversation"
    InputType.BATCH -> "Batch"
}

/** Compact "EN → ID" style language pair line. */
private fun languageLine(sourceCode: String, targetCode: String): String =
    "${sourceCode.ifBlank { "?" }.uppercase()} → ${targetCode.ifBlank { "?" }.uppercase()}"
