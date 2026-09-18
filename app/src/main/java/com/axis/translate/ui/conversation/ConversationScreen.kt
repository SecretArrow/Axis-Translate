package com.axis.translate.ui.conversation

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.axis.translate.domain.model.ConversationTurn
import com.axis.translate.ui.components.EmptyState
import com.axis.translate.ui.components.ErrorBanner
import com.axis.translate.ui.components.LanguageBar
import com.axis.translate.util.AndroidUtils
import com.axis.translate.util.rememberContainer

/**
 * Conversation screen: chat-style back-and-forth translation between two
 * languages, with offline voice input, per-turn speak/copy/delete, and a
 * flippable direction chip.
 */
@Composable
fun ConversationScreen(modifier: Modifier = Modifier) {
    val container = rememberContainer()
    val vm: ConversationViewModel = viewModel(factory = ConversationViewModel.factory(container))
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current

    var showVoiceHint by remember { mutableStateOf(false) }
    var menuForTurn by remember { mutableStateOf<String?>(null) }
    val turnsListState = rememberLazyListState()

    // Keep the newest turn visible: chat bubbles arrive below the fold
    // otherwise (LazyColumn does not follow its content growth).
    LaunchedEffect(state.turns.size) {
        if (state.turns.isNotEmpty()) {
            turnsListState.animateScrollToItem(state.turns.size - 1)
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val recognized = container.speechRecognizer.extractResult(
            result.resultCode,
            result.data
        )
        if (!recognized.isNullOrBlank()) {
            vm.setInput(recognized)
        }
    }

    Column(modifier.fillMaxSize()) {
        // --- Header: language pair + direction control ---
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            LanguageBar(
                source = state.langA,
                target = state.langB,
                onSourceClick = {},
                onTargetClick = {},
                onSwap = { vm.swapLanguages() }
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = { vm.toggleDirection() },
                    label = {
                        Text(
                            if (state.aToB) {
                                "${state.langA.code.uppercase()} → ${state.langB.code.uppercase()}"
                            } else {
                                "${state.langB.code.uppercase()} → ${state.langA.code.uppercase()}"
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.SwapVert,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize)
                        )
                    }
                )
                Spacer(Modifier.weight(1f))
                if (state.turns.isNotEmpty()) {
                    TextButton(onClick = { vm.clearAll() }) {
                        Text("Clear")
                    }
                }
            }
        }

        // --- Error banner with retry of the last turn ---
        state.error?.let { error ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ErrorBanner(
                    message = error,
                    onDismiss = { vm.dismissError() },
                    modifier = Modifier.weight(1f)
                )
                if (state.turns.isNotEmpty()) {
                    TextButton(
                        onClick = { vm.retryLast() },
                        enabled = !state.translating
                    ) {
                        Text("Retry")
                    }
                }
            }
        }

        // --- Translation progress ---
        if (state.translating) {
            Column {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(
                    onClick = { vm.cancelTranslation() },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 4.dp)
                ) {
                    Text("Cancel")
                }
            }
        }

        // --- Turns ---
        if (state.turns.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EmptyState(
                    icon = Icons.Outlined.Forum,
                    title = "Conversation mode",
                    subtitle = "Translate back and forth in one place",
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        } else {
            LazyColumn(
                state = turnsListState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.turns, key = { it.id }) { turn ->
                    val fromA = turn.source.code == state.langA.code
                    TurnCard(
                        turn = turn,
                        fromA = fromA,
                        menuOpen = menuForTurn == turn.id,
                        onMenuOpen = { open -> menuForTurn = if (open) turn.id else null },
                        onCopy = { AndroidUtils.copyToClipboard(context, turn.translated) },
                        onSpeak = {
                            container.textSpeaker.speak(turn.translated, turn.target.code)
                        },
                        onDelete = { vm.deleteTurn(turn.id) }
                    )
                }
            }
        }

        // --- Offline voice hint ---
        if (showVoiceHint) {
            Text(
                "Offline voice recognition is not available on this device.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }

        // --- Input row ---
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = vm::setInput,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Say something…") },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { vm.send() })
            )
            IconButton(
                onClick = {
                    if (container.speechRecognizer.isOfflineAvailable(context)) {
                        showVoiceHint = false
                        voiceLauncher.launch(
                            container.speechRecognizer.createRecognizeIntent(null)
                        )
                    } else {
                        showVoiceHint = true
                    }
                }
            ) {
                Icon(Icons.Filled.Mic, contentDescription = "Voice input")
            }
            Button(
                onClick = { vm.send() },
                enabled = state.input.isNotBlank() && !state.translating
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

/** One chat bubble: original + translated + overflow actions. */
@Composable
private fun TurnCard(
    turn: ConversationTurn,
    fromA: Boolean,
    menuOpen: Boolean,
    onMenuOpen: (Boolean) -> Unit,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(
                topStart = if (fromA) 20.dp else 4.dp,
                topEnd = if (fromA) 4.dp else 20.dp,
                bottomEnd = if (fromA) 4.dp else 20.dp,
                bottomStart = if (fromA) 20.dp else 4.dp
            ),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp,
            modifier = Modifier
                .align(if (fromA) Alignment.CenterStart else Alignment.CenterEnd)
                .widthIn(max = 340.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${turn.source.code.uppercase()} → ${turn.target.code.uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        IconButton(
                            onClick = { onMenuOpen(true) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "More actions",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { onMenuOpen(false) }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Copy translated") },
                                leadingIcon = {
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                                },
                                onClick = {
                                    onCopy()
                                    onMenuOpen(false)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Speak") },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                                },
                                onClick = {
                                    onSpeak()
                                    onMenuOpen(false)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Delete, contentDescription = null)
                                },
                                onClick = {
                                    onDelete()
                                    onMenuOpen(false)
                                }
                            )
                        }
                    }
                }
                Text(
                    turn.original,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    turn.translated,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    AndroidUtils.formatDate(turn.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
