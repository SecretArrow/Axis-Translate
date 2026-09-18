package com.axis.translate.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.axis.translate.domain.model.FavoriteItem
import com.axis.translate.domain.model.GlossaryTerm
import com.axis.translate.domain.model.HistoryItem
import com.axis.translate.domain.model.InputType

/**
 * Room representation of a [HistoryItem].
 *
 * [HistoryEntity.inputType] stores the enum name; unknown values degrade to
 * [InputType.TEXT] on mapping so an app downgrade cannot crash the list UI.
 */
@Entity(
    tableName = "history",
    indices = [
        Index("timestamp"),
        Index("isFavorite")
    ]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestamp: Long,
    val sourceCode: String,
    val targetCode: String,
    val sourceText: String,
    val translatedText: String,
    val inputType: String,
    val isFavorite: Boolean,
    val detectedLanguageCode: String?,
    val photoPath: String?,
    val ocrText: String?,
    val durationMs: Long
) {
    fun toDomain(): HistoryItem = HistoryItem(
        id = id,
        timestamp = timestamp,
        sourceCode = sourceCode,
        targetCode = targetCode,
        sourceText = sourceText,
        translatedText = translatedText,
        inputType = runCatching { InputType.valueOf(inputType) }.getOrDefault(InputType.TEXT),
        isFavorite = isFavorite,
        detectedLanguageCode = detectedLanguageCode,
        photoPath = photoPath,
        ocrText = ocrText,
        durationMs = durationMs
    )

    companion object {
        fun fromDomain(item: HistoryItem): HistoryEntity = HistoryEntity(
            id = item.id,
            timestamp = item.timestamp,
            sourceCode = item.sourceCode,
            targetCode = item.targetCode,
            sourceText = item.sourceText,
            translatedText = item.translatedText,
            inputType = item.inputType.name,
            isFavorite = item.isFavorite,
            detectedLanguageCode = item.detectedLanguageCode,
            photoPath = item.photoPath,
            ocrText = item.ocrText,
            durationMs = item.durationMs
        )
    }
}

/** Room representation of a [FavoriteItem]. */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestamp: Long,
    val sourceCode: String,
    val targetCode: String,
    val sourceText: String,
    val translatedText: String,
    val note: String = ""
) {
    fun toDomain(): FavoriteItem = FavoriteItem(
        id = id,
        timestamp = timestamp,
        sourceCode = sourceCode,
        targetCode = targetCode,
        sourceText = sourceText,
        translatedText = translatedText,
        note = note
    )

    companion object {
        fun fromDomain(item: FavoriteItem): FavoriteEntity = FavoriteEntity(
            id = item.id,
            timestamp = item.timestamp,
            sourceCode = item.sourceCode,
            targetCode = item.targetCode,
            sourceText = item.sourceText,
            translatedText = item.translatedText,
            note = item.note
        )
    }
}

/** Room representation of a [GlossaryTerm]. */
@Entity(tableName = "glossary")
data class GlossaryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val source: String,
    val target: String,
    val sourceCode: String,
    val targetCode: String,
    val caseSensitive: Boolean,
    val enabled: Boolean,
    val createdAt: Long
) {
    fun toDomain(): GlossaryTerm = GlossaryTerm(
        id = id,
        source = source,
        target = target,
        sourceCode = sourceCode,
        targetCode = targetCode,
        caseSensitive = caseSensitive,
        enabled = enabled,
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(term: GlossaryTerm): GlossaryEntity = GlossaryEntity(
            id = term.id,
            source = term.source,
            target = term.target,
            sourceCode = term.sourceCode,
            targetCode = term.targetCode,
            caseSensitive = term.caseSensitive,
            enabled = term.enabled,
            createdAt = term.createdAt
        )
    }
}
