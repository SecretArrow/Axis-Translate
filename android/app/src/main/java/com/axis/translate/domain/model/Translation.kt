package com.axis.translate.domain.model

/** How the source text entered the app. */
enum class InputType {
    TEXT,
    PHOTO,
    OCR,
    VOICE,
    DOCUMENT,
    CLIPBOARD,
    SHARE,
    CONVERSATION,
    BATCH
}

/** Optional translation style preference. Only applied when the model supports instruction control. */
enum class TranslationStyle(val label: String) {
    STANDARD("Standard"),
    NATURAL("Natural"),
    FORMAL("Formal"),
    CASUAL("Casual")
}

/** A single translation request flowing through the domain layer. */
data class TranslationRequest(
    val text: String,
    val source: Language,
    val target: Language,
    val inputType: InputType = InputType.TEXT,
    val glossary: List<GlossaryTerm> = emptyList(),
    val style: TranslationStyle = TranslationStyle.STANDARD,
    val maxOutputTokens: Int = 512
) {
    init {
        require(text.isNotBlank()) { "Translation text must not be blank" }
    }
}

/** Result of a completed translation. */
data class TranslationResult(
    val translatedText: String,
    val detectedLanguage: Language? = null,
    val durationMs: Long = 0L,
    val tokensGenerated: Int = 0,
    val cancelled: Boolean = false
)

/** High-level state of the translation subsystem, observed by the UI. */
sealed interface TranslationState {
    /** No model loaded yet — app usable, translation will prompt model install. */
    data object Idle : TranslationState

    /** Model file is being mmapped / context is being initialized. */
    data class LoadingModel(val message: String = "Loading AI model…") : TranslationState

    /** Engine is ready for inference. */
    data object Ready : TranslationState

    /** Inference in flight; [progress] is null when indeterminate, [partial] holds streamed output. */
    data class Translating(
        val progress: Float? = null,
        val partial: String? = null,
        val currentChunk: Int = 0,
        val totalChunks: Int = 1
    ) : TranslationState

    /** Recoverable error with a user-presentable message. */
    data class Error(val message: String) : TranslationState
}

/** Failure types surfaced by the engine, mapped to friendly UI strings at the edge. */
sealed class TranslationException(message: String) : Exception(message) {
    class ModelNotInstalled : TranslationException("AI model is not installed.")
    class ModelCorrupted : TranslationException("AI model verification failed.")
    class NotEnoughMemory : TranslationException("Not enough memory to process this request.")
    class InferenceError(message: String) : TranslationException(message)
    class UnsupportedLanguage(val language: Language) :
        TranslationException("This language is not supported by the installed model.")
    class Cancelled : TranslationException("Translation cancelled.")
    class EmptyInput : TranslationException("Please enter some text to translate.")
}
