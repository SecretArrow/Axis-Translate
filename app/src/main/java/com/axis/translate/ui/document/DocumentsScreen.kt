package com.axis.translate.ui.document

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.axis.translate.ui.components.EmptyState
import com.axis.translate.ui.components.ErrorBanner
import com.axis.translate.ui.navigation.PendingInput
import com.axis.translate.util.AndroidUtils
import com.axis.translate.util.rememberContainer

/** Document types offered in the system file picker (SAF). */
private val DOCUMENT_MIME_TYPES = arrayOf(
    "text/plain",
    "text/markdown",
    "text/html",
    "application/octet-stream",
    "text/x-markdown"
)

/**
 * Documents screen: pick (or receive via share) a TXT / Markdown / HTML
 * document, translate it on-device and export a translated copy.
 */
@Composable
fun DocumentsScreen(modifier: Modifier = Modifier) {
    val container = rememberContainer()
    val vm: DocumentsViewModel = viewModel(factory = DocumentsViewModel.factory(container))
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.pick(context, it) } }

    // A document shared into the app is routed here by the share-target handoff.
    LaunchedEffect(vm, context) {
        PendingInput.sharedDocumentUri.collect { uri ->
            if (uri != null) {
                vm.pick(context, uri)
                PendingInput.sharedDocumentUri.value = null
            }
        }
    }

    // Share exactly once per published export URI: the URI is consumed right
    // after the share sheet launches, so a re-export of the same file shares
    // again while a configuration change alone never re-shares.
    LaunchedEffect(state.exportUri) {
        state.exportUri?.let { uri ->
            AndroidUtils.shareFile(context, uri, "text/plain", "Share translated document")
            vm.onExportConsumed()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        state.error?.let { message ->
            ErrorBanner(message = message, onDismiss = vm::dismissError)
            Spacer(Modifier.height(12.dp))
        }

        val doc = state.document
        if (doc == null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                EmptyState(
                    icon = Icons.Outlined.Description,
                    title = "Translate a document",
                    subtitle = "Supports TXT, Markdown and HTML"
                )
                if (state.translating) {
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Reading document…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { picker.launch(DOCUMENT_MIME_TYPES) }) {
                        Text("Choose document")
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Loaded document card: title, size and content preview.
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = doc.title,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${doc.text.length} chars",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = doc.text.take(500) + if (doc.text.length > 500) "…" else "",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (state.translating) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "Translating…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(onClick = vm::cancelTranslation) {
                        Text("Cancel")
                    }
                } else if (state.translated != null) {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = "Translation",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(8.dp))
                            SelectionContainer {
                                Text(
                                    text = state.translated.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .heightIn(max = 320.dp)
                                        .verticalScroll(rememberScrollState())
                                )
                            }
                        }
                    }
                } else if (state.translated == null) {
                    Button(
                        onClick = { vm.translate() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Translate document")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { vm.export(context) },
                        enabled = state.translated != null && !state.translating
                    ) {
                        Text("Export & Share")
                    }
                    OutlinedButton(onClick = vm::reset) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
