package com.axis.translate.ui.glossary

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.axis.translate.domain.model.GlossaryTerm
import com.axis.translate.ui.components.ConfirmDialog
import com.axis.translate.ui.components.EmptyState
import com.axis.translate.util.rememberContainer

/**
 * Glossary screen: enforced source -> target term pairs used during
 * translation. Supports enabling/disabling, adding, editing and deleting terms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossaryScreen(modifier: Modifier = Modifier) {
    val container = rememberContainer()
    val vm: GlossaryViewModel = viewModel(factory = GlossaryViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GlossaryTerm?>(null) }
    var deleting by remember { mutableStateOf<GlossaryTerm?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Glossary",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Terms in the glossary are enforced during translation when the glossary is enabled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = { vm.toggleEnabled() }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    state.terms.isEmpty() && state.loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }

                    state.terms.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.MenuBook,
                        title = "Glossary is empty",
                        subtitle = "Add a term to keep translations consistent.",
                        modifier = Modifier.fillMaxSize()
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.terms, key = { it.id }) { term ->
                            GlossaryTermCard(
                                term = term,
                                onEdit = { editing = term },
                                onDelete = { deleting = term }
                            )
                        }
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            text = { Text("Add term") },
            icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        )
    }

    if (showAddDialog) {
        GlossaryTermDialog(
            initial = null,
            onDismiss = { showAddDialog = false },
            onConfirm = { term ->
                vm.add(term)
                showAddDialog = false
            }
        )
    }

    editing?.let { term ->
        GlossaryTermDialog(
            initial = term,
            onDismiss = { editing = null },
            onConfirm = { updated ->
                vm.update(updated)
                editing = null
            }
        )
    }

    deleting?.let { term ->
        ConfirmDialog(
            title = "Delete term?",
            message = "“${term.source}” will be removed from the glossary.",
            confirmLabel = "Delete",
            onConfirm = {
                vm.delete(term.id)
                deleting = null
            },
            onDismiss = { deleting = null }
        )
    }
}

/** A single glossary term row with edit / delete overflow actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlossaryTermCard(term: GlossaryTerm, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "“${term.source}” → “${term.target}”",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = languageLine(term.sourceCode, term.targetCode),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

/** Add/edit dialog for a glossary term with its language pair codes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlossaryTermDialog(initial: GlossaryTerm?, onDismiss: () -> Unit, onConfirm: (GlossaryTerm) -> Unit) {
    var source by remember(initial) { mutableStateOf(initial?.source ?: "") }
    var target by remember(initial) { mutableStateOf(initial?.target ?: "") }
    var sourceCode by remember(initial) { mutableStateOf(initial?.sourceCode ?: "en") }
    var targetCode by remember(initial) { mutableStateOf(initial?.targetCode ?: "id") }

    val valid = source.isNotBlank() && target.isNotBlank() &&
        sourceCode.isNotBlank() && targetCode.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add term" else "Edit term") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    label = { Text("Source term") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("Target term") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = sourceCode,
                        onValueChange = { sourceCode = it },
                        label = { Text("From") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = targetCode,
                        onValueChange = { targetCode = it },
                        label = { Text("To") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(
                        GlossaryTerm(
                            id = initial?.id ?: 0L,
                            source = source.trim(),
                            target = target.trim(),
                            sourceCode = sourceCode.trim().lowercase(),
                            targetCode = targetCode.trim().lowercase(),
                            caseSensitive = initial?.caseSensitive ?: false,
                            enabled = initial?.enabled ?: true,
                            createdAt = initial?.createdAt ?: System.currentTimeMillis()
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Compact "EN → ID" style language pair line. */
private fun languageLine(sourceCode: String, targetCode: String): String =
    "${sourceCode.ifBlank { "?" }.uppercase()} → ${targetCode.ifBlank { "?" }.uppercase()}"
