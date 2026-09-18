package com.axis.translate.domain.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * [TextSpeaker] backed by the platform text-to-speech engine (SPEC #20).
 * Audio is synthesized fully on-device from locally installed language data —
 * text is never shipped to a remote TTS API.
 */
class AndroidTextSpeaker(private val context: Context) : TextSpeaker {

    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null

    override suspend fun initialize(): Result<Unit> = withContext(Dispatchers.Main) {
        val pending = ready
        if (pending != null) {
            // Already initialized or an initialization is in flight — adopt it.
            return@withContext awaitReady(pending)
        }

        val deferred = CompletableDeferred<Boolean>()
        ready = deferred
        val engine = try {
            TextToSpeech(context.applicationContext) { status ->
                deferred.complete(status == TextToSpeech.SUCCESS)
            }
        } catch (error: Exception) {
            ready = null
            return@withContext Result.failure(error)
        }
        tts = engine

        if (awaitReady(deferred).isSuccess) {
            Result.success(Unit)
        } else {
            // Some OEM engines never invoke the init callback — time out and
            // allow a later retry rather than reporting a zombie engine.
            runCatching { engine.shutdown() }
            tts = null
            ready = null
            Result.failure(IllegalStateException("Text-to-speech engine failed to initialize"))
        }
    }

    private suspend fun awaitReady(deferred: CompletableDeferred<Boolean>): Result<Unit> {
        val ok = withTimeoutOrNull(INIT_TIMEOUT_MS) { deferred.await() } ?: false
        return if (ok) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Text-to-speech engine failed to initialize"))
        }
    }

    override fun isLanguageAvailable(languageCode: String): Boolean {
        val engine = tts ?: return false
        val result = engine.isLanguageAvailable(Locale.forLanguageTag(languageCode))
        return result == TextToSpeech.LANG_AVAILABLE ||
            result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
            result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
    }

    override fun speak(text: String, languageCode: String) {
        if (text.isBlank()) return
        // No engine support -> deliberately silent; the caller is expected to
        // surface "Offline voice is unavailable for this language."
        if (!isLanguageAvailable(languageCode)) return
        val engine = tts ?: return
        engine.language = Locale.forLanguageTag(languageCode)
        engine.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "axis-${System.currentTimeMillis()}",
        )
    }

    override fun stop() {
        tts?.stop()
    }

    override fun close() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = null
    }

    companion object {
        private const val INIT_TIMEOUT_MS = 10_000L
    }
}
