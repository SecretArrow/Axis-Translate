package com.axis.translate.domain

import com.axis.translate.domain.model.GlossaryTerm
import com.axis.translate.domain.model.Language
import com.axis.translate.domain.model.TranslationStyle

/**
 * Splits long input into inference-sized chunks without breaking words
 * (SPEC #16: paragraph -> sentence -> punctuation -> token boundary).
 */
interface TextChunker {
    /**
     * @param maxChars soft maximum chunk length in characters.
     * @return chunks preserving the original text when joined with "\n\n".
     */
    fun chunk(text: String, maxChars: Int = 900): List<String>
}

/** Result of local language detection (SPEC #31). */
data class LanguageDetection(
    val language: Language,
    val confidence: Float,
    val alternatives: List<Language> = emptyList(),
) {
    val isConfident: Boolean get() = confidence >= CONFIDENCE_THRESHOLD

    companion object {
        const val CONFIDENCE_THRESHOLD = 0.6f
    }
}

/** Fully local language detection — never calls a remote API. */
interface LanguageDetector {
    fun detect(text: String): LanguageDetection?
}

/**
 * Builds the inference prompt for translation requests.
 * Implementations must not invent model capabilities (SPEC #30).
 */
interface PromptBuilder {
    /**
     * Prompt instructing the model to translate [text] from [source] to [target].
     * [source] may be [Language.AUTO]; the builder receives the detected language
     * via [detectedLanguage] when available.
     */
    fun buildTranslationPrompt(
        source: Language,
        target: Language,
        text: String,
        glossary: List<GlossaryTerm> = emptyList(),
        style: TranslationStyle = TranslationStyle.STANDARD,
        detectedLanguage: Language? = null,
    ): String
}
