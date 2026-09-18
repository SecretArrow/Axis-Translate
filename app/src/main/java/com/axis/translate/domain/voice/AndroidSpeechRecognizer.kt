package com.axis.translate.domain.voice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * [SpeechRecognitionHelper] backed by the platform speech recognizer
 * (SPEC #19).
 *
 * Recognition is launched as an activity result so the user sees the system
 * recognizer UI; [RecognizerIntent.EXTRA_PREFER_OFFLINE] is always requested.
 * Whether the engine can truly honor offline mode is only knowable at
 * runtime, so [isOfflineAvailable] is an honest best-effort probe.
 */
class AndroidSpeechRecognizer : SpeechRecognitionHelper {

    override fun isOfflineAvailable(context: Context): Boolean = try {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            false
        } else {
            // Best-effort offline probe: the recognize intent must resolve to
            // at least one handler activity on this device.
            isRecognizeIntentResolvable(context)
        }
    } catch (_: Exception) {
        false
    }

    private fun isRecognizeIntentResolvable(context: Context): Boolean {
        val intent = createRecognizeIntent(null)
        val packageManager = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0)
            ).isNotEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        }
    }

    override fun createRecognizeIntent(languageHint: String?): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageHint ?: Locale.getDefault().toString())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now")
    }

    override fun extractResult(resultCode: Int, data: Intent?): String? = if (resultCode == Activity.RESULT_OK) {
        data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
    } else {
        null
    }

    override fun errorMessage(errorCode: Int): String = when (errorCode) {
        SpeechRecognizer.ERROR_AUDIO ->
            "Audio recording error. The microphone may be blocked or in use."
        SpeechRecognizer.ERROR_CLIENT ->
            "Voice recognition encountered a client error."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission is required for voice input."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Network timeout while recognizing speech. Check your connection."
        SpeechRecognizer.ERROR_NETWORK ->
            "Network error while recognizing speech."
        SpeechRecognizer.ERROR_NO_MATCH ->
            "No matching speech was found. Please try again."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "The speech recognizer is busy. Please try again shortly."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "No speech input was detected. Please try again."
        SpeechRecognizer.ERROR_SERVER ->
            "Voice recognition service error."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
            "Too many voice recognition requests. Try again later."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            "This language is not supported for voice input."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "The requested language is unavailable for voice recognition."
        else ->
            "Voice recognition failed. Please try again."
    }
}
