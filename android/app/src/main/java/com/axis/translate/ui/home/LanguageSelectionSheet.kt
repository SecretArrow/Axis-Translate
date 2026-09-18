package com.axis.translate.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.axis.translate.domain.model.Language
import com.axis.translate.ui.components.SectionHeader
import com.axis.translate.ui.theme.AxisAmber

/**
 * Language picker bottom sheet: search, favorite languages, recent pairs,
 * and the full catalog as M3 list items with native names. When [isTarget]
 * is false the list is headed by [Language.AUTO] (auto-detect).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSelectionSheet(
    isTarget: Boolean,
    languages: List<Language>,
    recents: List<Pair<String, String>>,
    favorites: List<String>,
    onSelect: (Language) -> Unit,
    onDismiss: () -> Unit,
    onToggleFavorite: (Language) -> Unit,
    onRecentPairSelected: (Pair<String, String>) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }

    val filtered = remember(query, languages) {
        if (query.isBlank()) {
            languages
        } else {
            languages.filter { it.matchesQuery(query) }
        }
    }
    val favoriteLanguages = remember(languages, favorites) {
        languages.filter { favorites.contains(it.code) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .imePadding()
        ) {
            Text(
                text = if (isTarget) "Translate to" else "Translate from",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search languages") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Clear search"
                            )
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge
            )
            Spacer(Modifier.height(12.dp))

            if (query.isBlank() && recents.isNotEmpty()) {
                SectionHeader(text = "Recent pairs")
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(
                        recents.distinct(),
                        key = { "${it.first}>${it.second}" }
                    ) { pair ->
                        AssistChip(
                            onClick = { onRecentPairSelected(pair) },
                            label = {
                                Text("${pair.first.uppercase()} → ${pair.second.uppercase()}")
                            }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                if (!isTarget && (query.isBlank() || Language.AUTO.matchesQuery(query))) {
                    item(key = Language.AUTO.code) {
                        LanguageRow(
                            language = Language.AUTO,
                            isFavorite = false,
                            onSelect = onSelect,
                            onToggleFavorite = null
                        )
                    }
                }
                if (query.isBlank() && favoriteLanguages.isNotEmpty()) {
                    item(key = "favorites_header") {
                        SectionHeader(text = "Favorites")
                        Spacer(Modifier.height(4.dp))
                    }
                    items(
                        favoriteLanguages,
                        key = { "fav_${it.code}" }
                    ) { language ->
                        LanguageRow(
                            language = language,
                            isFavorite = true,
                            onSelect = onSelect,
                            onToggleFavorite = { onToggleFavorite(language) }
                        )
                    }
                    item(key = "all_header") {
                        Spacer(Modifier.height(8.dp))
                        SectionHeader(text = "All languages")
                        Spacer(Modifier.height(4.dp))
                    }
                }
                items(
                    filtered,
                    key = { it.code }
                ) { language ->
                    LanguageRow(
                        language = language,
                        isFavorite = favorites.contains(language.code),
                        onSelect = onSelect,
                        onToggleFavorite = { onToggleFavorite(language) }
                    )
                }
                if (filtered.isEmpty() && query.isNotBlank() && (isTarget || !Language.AUTO.matchesQuery(query))) {
                    item(key = "no_results") {
                        Text(
                            text = "No languages found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun LanguageRow(language: Language, isFavorite: Boolean, onSelect: (Language) -> Unit, onToggleFavorite: (() -> Unit)?, modifier: Modifier = Modifier) {
    val secondary = if (language.nativeName != language.displayName) {
        "${language.displayName} · ${language.code.uppercase()}"
    } else {
        language.code.uppercase()
    }
    ListItem(
        headlineContent = {
            Text(
                text = language.nativeName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = secondary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                if (language.isAuto) {
                    Icon(
                        imageVector = Icons.Outlined.Language,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(18.dp)
                    )
                } else {
                    Text(
                        text = language.code.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        },
        trailingContent = {
            if (onToggleFavorite != null) {
                IconButton(onClick = { onToggleFavorite() }) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (isFavorite) {
                            "Remove from favorites"
                        } else {
                            "Add to favorites"
                        },
                        tint = if (isFavorite) {
                            AxisAmber
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.clickable { onSelect(language) }
    )
}

/** Case-insensitive match across display name, native name, and code. */
private fun Language.matchesQuery(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return displayName.contains(q, ignoreCase = true) ||
        nativeName.contains(q, ignoreCase = true) ||
        code.contains(q, ignoreCase = true)
}
