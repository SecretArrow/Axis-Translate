package com.axis.translate.inference

import com.axis.translate.domain.EngineConfig
import com.axis.translate.domain.EngineRuntimeInfo
import com.axis.translate.domain.TranslationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production [TranslationEngine] backed by llama.cpp via [LlamaBridge].
 *
 * All state transitions ([loadModel]/[unload]/[complete]/[stop]) are guarded
 * by [lock]; the heavy generation itself runs on [Dispatchers.Default] while
 * holding nothing but the bridge reference, so [stop] stays responsive while
 * a completion is in flight.
 */
class LlamaEngine : TranslationEngine {

    private var bridge: LlamaBridge? = null
    private var config: EngineConfig? = null
    private val lock = Any()

    override val isLoaded: Boolean
        get() = synchronized(lock) { bridge != null }

    override fun loadModel(modelPath: String, config: EngineConfig): Result<Unit> = synchronized(lock) {
        runCatching {
            bridge?.destroy()
            bridge = null
            this.config = config
            bridge = LlamaBridge(modelPath, config.threads, config.contextLength)
        }
    }

    override fun unload() {
        synchronized(lock) {
            bridge?.destroy()
            bridge = null
        }
    }

    override suspend fun complete(prompt: String, maxTokens: Int, stopSequence: String?): Result<String> = withContext(Dispatchers.Default) {
        val b = synchronized(lock) { bridge }
            ?: return@withContext Result.failure(IllegalStateException("Model not loaded"))
        val out = b.complete(prompt, maxTokens, config?.temperature ?: 0.1f, stopSequence)
            ?: return@withContext Result.failure(RuntimeException("Generation failed"))
        Result.success(out)
    }

    override fun stop() {
        synchronized(lock) { bridge }?.stop()
    }

    override fun runtimeInfo(): EngineRuntimeInfo? = synchronized(lock) { config }?.let {
        EngineRuntimeInfo(
            name = "llama.cpp",
            version = LlamaBridge.nativeVersion(),
            threads = it.threads,
            contextLength = it.contextLength
        )
    }

    override fun close() = unload()
}
