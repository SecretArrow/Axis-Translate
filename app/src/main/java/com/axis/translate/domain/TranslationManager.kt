package com.axis.translate.domain

import com.axis.translate.domain.model.GlossaryTerm
import com.axis.translate.domain.model.Language
import com.axis.translate.domain.model.TranslationException
import com.axis.translate.domain.model.TranslationRequest
import com.axis.translate.domain.model.TranslationResult
import com.axis.translate.domain.model.TranslationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Orchestrates the full translation pipeline (SPEC #1, #15–#18):
 *
 * Input -> Normalize -> Language Detection -> Chunking -> Prompt -> Engine -> Reassemble -> Output
 *
 * Owns the engine lifecycle (lazy model load, unload on demand), cooperative
 * cancellation, and per-chunk progress reporting.
 */
class TranslationManager(
    private val engineFactory: () -> TranslationEngine,
    private val modelPathProvider: () -> String?,
    private val engineConfigProvider: suspend () -> EngineConfig,
    private val textChunker: TextChunker,
    private val promptBuilder: PromptBuilder,
    private val languageDetector: LanguageDetector,
) {

    private val mutex = Mutex()
    private val stateFlow = MutableStateFlow<TranslationState>(TranslationState.Idle)
    val state: StateFlow<TranslationState> = stateFlow.asStateFlow()

    private val stopRequested = AtomicBoolean(false)
    private var engine: TranslationEngine? = null

    /** True when the underlying model file exists (engine may still need loading). */
    fun hasModelFile(): Boolean = modelPathProvider() != null

    /** Current engine info for Developer Mode (SPEC #69). */
    fun runtimeInfo(): EngineRuntimeInfo? = engine?.runtimeInfo()

    /** Voluntary unload — called on memory pressure or from Model Manager. */
    suspend fun unloadEngine() {
        mutex.withLock {
            withContext(Dispatchers.Default) {
                engine?.unload()
            }
            engine = null
            stateFlow.value = TranslationState.Idle
        }
    }

    /** Forces reload on the next translation (after model install/remove). */
    suspend fun reset() {
        unloadEngine()
    }

    private suspend fun ensureEngine(): TranslationEngine = mutex.withLock {
        engine?.takeIf { it.isLoaded }?.let { return it }

        val path = modelPathProvider()
            ?: throw TranslationException.ModelNotInstalled()

        stateFlow.value = TranslationState.LoadingModel()
        val e = engineFactory()
        val loadResult = withContext(Dispatchers.Default) {
            e.loadModel(path, engineConfigProvider())
        }
        if (loadResult.isFailure) {
            engine = null
            val error = loadResult.exceptionOrNull()
            val message = when (error) {
                is TranslationException -> error.message ?: "Model failed to load"
                else -> "The local AI engine could not start." +
                    (error?.message?.let { " $it" } ?: "")
            }
            stateFlow.value = TranslationState.Error(message)
            throw TranslationException.InferenceError(message)
        }
        engine = e
        stateFlow.value = TranslationState.Ready
        return e
    }

    /**
     * Translates [request], chunk by chunk, preserving paragraph structure.
     * Cancellation-aware: throws [TranslationException.Cancelled] when [stop]
     * was requested mid-flight; cooperative with coroutine cancellation.
     */
    suspend fun translate(request: TranslationRequest): TranslationResult {
        stopRequested.set(false)
        val startedAt = System.currentTimeMillis()

        val normalized = normalize(request.text)
        if (normalized.isBlank()) {
            throw TranslationException.EmptyInput()
        }

        var detected: Language? = null
        val source: Language = if (request.source.isAuto) {
            detected = languageDetector.detect(normalized)?.language
            detected ?: request.source
        } else {
            request.source
        }

        val chunks = textChunker.chunk(normalized)
        val outputs = ArrayList<String>(chunks.size)

        return try {
            val e = ensureEngine()
            stateFlow.value = TranslationState.Translating(
                progress = null,
                partial = null,
                currentChunk = 0,
                totalChunks = chunks.size,
            )

            var generated = 0
            for ((index, chunk) in chunks.withIndex()) {
                if (stopRequested.get()) throw TranslationException.Cancelled()

                val prompt = promptBuilder.buildTranslationPrompt(
                    source = request.source,
                    target = request.target,
                    text = chunk,
                    glossary = request.glossary.filter(GlossaryTerm::enabled),
                    style = request.style,
                    detectedLanguage = detected ?: source,
                )

                val output = e.complete(
                    prompt = prompt,
                    maxTokens = request.maxOutputTokens,
                ).getOrElse { error ->
                    if (stopRequested.get()) {
                        throw TranslationException.Cancelled()
                    }
                    throw error
                }

                generated += output.length
                outputs += output

                stateFlow.value = TranslationState.Translating(
                    progress = (index + 1).toFloat() / chunks.size,
                    partial = outputs.joinToString("\n\n"),
                    currentChunk = index + 1,
                    totalChunks = chunks.size,
                )
            }

            val result = TranslationResult(
                translatedText = reassemble(outputs),
                detectedLanguage = detected ?: source,
                durationMs = System.currentTimeMillis() - startedAt,
                tokensGenerated = generated,
            )
            stateFlow.value = TranslationState.Ready
            result
        } catch (ce: CancellationException) {
            stateFlow.value = TranslationState.Idle
            throw TranslationException.Cancelled()
        } catch (cancelled: TranslationException.Cancelled) {
            stateFlow.value = TranslationState.Idle
            throw cancelled
        } catch (error: TranslationException) {
            stateFlow.value = TranslationState.Error(error.message ?: "Translation failed")
            throw error
        } catch (error: Throwable) {
            stateFlow.value = TranslationState.Error(
                error.message ?: "The local AI engine could not complete the translation.",
            )
            throw TranslationException.InferenceError(error.message ?: "inference error")
        }
    }

    /** Requests the in-flight generation to stop (SPEC #18). */
    fun stop() {
        stopRequested.set(true)
        engine?.stop()
    }

    fun close() {
        engine?.close()
        engine = null
    }

    companion object {
        /** NFC + trim + collapse redundant blank lines. */
        fun normalize(text: String): String {
            val nfc = if (Normalizer.isNormalized(text, Normalizer.Form.NFC)) {
                text
            } else {
                Normalizer.normalize(text, Normalizer.Form.NFC)
            }
            return nfc.lineSequence()
                .map { it.trimEnd() }
                .joinToString("\n")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        }

        /**
         * Rejoins chunk outputs mirroring the original paragraph breaks
         * (chunks are joined exactly as they were split).
         */
        fun reassemble(outputs: List<String>): String = outputs.joinToString("\n\n").trim()

        fun estimateTokens(text: String): Int = (text.length / 4.0).roundToInt().coerceAtLeast(1)
    }
}
