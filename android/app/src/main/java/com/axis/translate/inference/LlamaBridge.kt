package com.axis.translate.inference

/**
 * Thin Kotlin wrapper over the native llama.cpp engine (libaxis_engine.so).
 *
 * The native side is exposed as five @JvmStatic externals on the companion
 * object so the JNI symbols resolve against this exact class. The engine
 * handle is opaque; 0 means "not created". Construction loads the GGUF model
 * synchronously (call from a worker dispatcher) and throws if loading fails.
 *
 * This class is not thread-safe by itself for [complete] — serialize access
 * via the owning [LlamaEngine], or accept that the native side serializes
 * generation internally with a mutex and cancellation flag.
 */
class LlamaBridge(modelPath: String, nThreads: Int, nCtx: Int) {

    private var handle: Long

    init {
        handle = nativeCreate(modelPath, nThreads, nCtx)
        if (handle == 0L) throw IllegalStateException("Failed to load model: $modelPath")
    }

    fun complete(prompt: String, maxTokens: Int, temperature: Float, stopSeq: String?): String? =
        nativeComplete(handle, prompt, maxTokens, temperature, stopSeq)

    fun stop() {
        nativeStop(handle)
    }

    @Synchronized
    fun destroy() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    companion object {
        init {
            System.loadLibrary("axis_engine")
        }

        @JvmStatic
        external fun nativeVersion(): String

        @JvmStatic
        external fun nativeCreate(modelPath: String, nThreads: Int, nCtx: Int): Long

        @JvmStatic
        external fun nativeComplete(handle: Long, prompt: String, maxTokens: Int, temperature: Float, stopSeq: String?): String?

        @JvmStatic
        external fun nativeStop(handle: Long)

        @JvmStatic
        external fun nativeDestroy(handle: Long)
    }
}
