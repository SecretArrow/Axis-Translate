package com.axis.translate.domain

import com.axis.translate.domain.model.TranslationResult

/** Configuration for the native inference engine. */
data class EngineConfig(
    val threads: Int = 4,
    val contextLength: Int = 2048,
    val temperature: Float = 0.1f,
)

/** Descriptive info about the running engine instance. */
data class EngineRuntimeInfo(
    val name: String,
    val version: String,
    val threads: Int,
    val contextLength: Int,
)

/**
 * Contract implemented by the real llama.cpp-backed engine
 * (see [com.axis.translate.inference.LlamaEngine]) and by the test double
 * (see [com.axis.translate.inference.FakeEngine]).
 *
 * All methods are safe to call from any thread; implementations must be
 * internally synchronized because native inference runs on worker threads.
 */
interface TranslationEngine {

    /** Whether a model is currently loaded and ready for [complete]. */
    val isLoaded: Boolean

    /** Synchronously (blocking) load a GGUF model. Call from a worker dispatcher. */
    fun loadModel(modelPath: String, config: EngineConfig): Result<Unit>

    /** Unload the model and free native memory. Idempotent. */
    fun unload()

    /**
     * Run a prompt completion against the loaded model.
     * Returns the generated text (without the prompt).
     */
    suspend fun complete(prompt: String, maxTokens: Int, stopSequence: String? = null): Result<String>

    /** Best-effort request to stop the in-flight generation. */
    fun stop()

    /** Info about the running engine, or null if no model loaded. */
    fun runtimeInfo(): EngineRuntimeInfo?

    /** Release all resources. */
    fun close()
}

/** Generic success holder with timing, for benchmark display. */
data class EngineCompletion(
    val result: TranslationResult,
    val promptTokens: Int = 0,
    val outputTokens: Int = 0,
    val tokensPerSecond: Float = 0f,
)
