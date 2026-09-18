package com.axis.translate.inference

import com.axis.translate.domain.EngineConfig
import com.axis.translate.domain.EngineRuntimeInfo
import com.axis.translate.domain.TranslationEngine
import kotlinx.coroutines.delay

/**
 * Test double for [TranslationEngine], swappable via
 * com.axis.translate.di.TestOverrides.engineFactoryOverride before the
 * activity launches (instrumented/e2e flows that must not depend on a
 * downloaded GGUF model).
 *
 * [responder] maps a prompt to the "translation"; [latencyMs] simulates
 * model inference latency (useful for cancel/progress UI tests).
 */
class FakeEngine(
    private val responder: (String) -> String = { "[axis-test] ${it.length}" },
    private val latencyMs: Long = 0,
) : TranslationEngine {

    @Volatile
    private var loaded = false

    override val isLoaded: Boolean
        get() = loaded

    override fun loadModel(modelPath: String, config: EngineConfig): Result<Unit> {
        loaded = true
        return Result.success(Unit)
    }

    override fun unload() {
        loaded = false
    }

    override suspend fun complete(prompt: String, maxTokens: Int, stopSequence: String?): Result<String> {
        if (latencyMs > 0) {
            delay(latencyMs)
        }
        return Result.success(responder(prompt))
    }

    override fun stop() {
        // No-op: the fake engine has no cancellable work.
    }

    override fun runtimeInfo(): EngineRuntimeInfo =
        EngineRuntimeInfo(
            name = "fake-engine",
            version = "1.0",
            threads = 1,
            contextLength = 2048,
        )

    override fun close() = unload()
}
