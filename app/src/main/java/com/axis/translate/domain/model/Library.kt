package com.axis.translate.domain.model

import java.util.UUID

/** A single history entry. */
data class HistoryItem(
    val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val sourceCode: String,
    val targetCode: String,
    val sourceText: String,
    val translatedText: String,
    val inputType: InputType = InputType.TEXT,
    val isFavorite: Boolean = false,
    val detectedLanguageCode: String? = null,
    val photoPath: String? = null,
    val ocrText: String? = null,
    val durationMs: Long = 0L,
) {
    fun toFavorite(): FavoriteItem = FavoriteItem(
        timestamp = timestamp,
        sourceCode = sourceCode,
        targetCode = targetCode,
        sourceText = sourceText,
        translatedText = translatedText,
    )
}

/** A starred translation. */
data class FavoriteItem(
    val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val sourceCode: String,
    val targetCode: String,
    val sourceText: String,
    val translatedText: String,
    val note: String = "",
)

/** A glossary term: enforced source -> target rendering during translation. */
data class GlossaryTerm(
    val id: Long = 0L,
    val source: String,
    val target: String,
    val sourceCode: String,
    val targetCode: String,
    val caseSensitive: Boolean = false,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
) {
    init {
        require(source.isNotBlank()) { "Glossary source term must not be blank" }
        require(target.isNotBlank()) { "Glossary target term must not be blank" }
    }
}

/** A conversation turn. */
data class ConversationTurn(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val source: Language,
    val target: Language,
    val original: String,
    val translated: String,
)

/** A batch translation task. */
data class BatchTask(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val sourceText: String,
    val source: Language,
    val target: Language,
    val sourceUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Batch queue item state. */
enum class BatchState { PENDING, RUNNING, DONE, FAILED, CANCELLED }
