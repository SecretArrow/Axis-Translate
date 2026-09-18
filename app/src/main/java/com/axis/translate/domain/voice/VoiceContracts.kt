package com.axis.translate.domain.voice

import android.content.Intent

/**
 * Voice input contract (SPEC #19): prefer offline recognition, never
 * silently send audio to a remote service.
 */
interface SpeechRecognitionHelper {
    /** True when the device exposes a speech recognizer with offline support. */
    fun isOfflineAvailable(): Boolean

    /** Intent launched via StartActivityForResult; results read from EXTRA_RESULTS. */
    fun createRecognizeIntent(languageHint: String?): Intent

    /** Extracts the best hypothesis from the activity result. */
    fun extractResult(resultCode: Int, data: Intent?): String?

    /** Human-readable error for the given SpeechRecognizer error code. */
    fun errorMessage(errorCode: Int): String
}

/**
 * Text-to-speech contract (SPEC #20): Android TTS engine, offline language
 * packs preferred; never ships text to a remote TTS API.
 */
interface TextSpeaker {
    suspend fun initialize(): Result<Unit>
    fun isLanguageAvailable(languageCode: String): Boolean
    fun speak(text: String, languageCode: String)
    fun stop()
    fun close()
}
