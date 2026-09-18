package com.axis.translate.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.axis.translate.domain.model.Language

/**
 * Source / target language selector row with a centered swap button.
 * Each side is a clickable chip (language code + display name + caret).
 */
@Composable
fun LanguageBar(
    source: Language,
    target: Language,
    onSourceClick: () -> Unit,
    onTargetClick: () -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LanguageChip(
            language = source,
            onClick = onSourceClick,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSwap) {
            Icon(
                imageVector = Icons.Outlined.Autorenew,
                contentDescription = "Swap languages",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        LanguageChip(
            language = target,
            onClick = onTargetClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LanguageChip(
    language: Language,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = language.code.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = language.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Outlined.ArrowDropDown,
                contentDescription = null,
            )
        }
    }
}
