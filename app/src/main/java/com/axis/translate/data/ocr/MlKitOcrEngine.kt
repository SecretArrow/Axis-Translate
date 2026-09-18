package com.axis.translate.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.axis.translate.domain.OcrEngine
import com.axis.translate.domain.OcrRegion
import com.axis.translate.domain.OcrResult
import com.google.mlkit.vision.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Fully offline OCR backed by the bundled ML Kit on-device recognizers.
 *
 * Five script-specific recognizers (Latin, Chinese, Japanese, Korean,
 * Devanagari) run sequentially over the same image; the result carrying the
 * longest non-blank text wins. Everything runs on device — no network access.
 */
class MlKitOcrEngine(private val context: Context) : OcrEngine {

    private val recognizersDelegate = lazy {
        listOf(
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            TextRecognition.getClient(ChineseTextRecognizerOptions()),
            TextRecognition.getClient(JapaneseTextRecognizerOptions()),
            TextRecognition.getClient(KoreanTextRecognizerOptions()),
            TextRecognition.getClient(DevanagariTextRecognizerOptions())
        )
    }

    private val recognizers: List<TextRecognizer> get() = recognizersDelegate.value

    override suspend fun recognize(image: Bitmap, rotationDegrees: Int): Result<OcrResult> {
        val input = InputImage.fromBitmap(image, rotationDegrees)
        var firstFailure: Throwable? = null
        var best: OcrResult? = null
        for (recognizer in recognizers) {
            val outcome = recognizeWith(recognizer, input)
            if (outcome.isFailure) {
                if (firstFailure == null) firstFailure = outcome.exceptionOrNull()
                continue
            }
            val candidate = outcome.getOrThrow()
            if (candidate.fullText.isNotBlank() &&
                (best == null || candidate.fullText.length > best.fullText.length)
            ) {
                best = candidate
            }
        }
        val winner = best
        return when {
            winner != null -> Result.success(winner)
            firstFailure != null -> Result.failure(firstFailure)
            else -> Result.success(OcrResult())
        }
    }

    private suspend fun recognizeWith(recognizer: TextRecognizer, input: InputImage): Result<OcrResult> = suspendCancellableCoroutine { continuation ->
        try {
            recognizer.process(input)
                .addOnSuccessListener { text ->
                    if (continuation.isActive) continuation.resume(Result.success(map(text)))
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resume(Result.failure(error))
                }
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resume(Result.failure(error))
        }
    }

    /** Flattens ML Kit blocks into one [OcrRegion] per recognized line. */
    internal fun map(text: Text): OcrResult {
        val regions = text.textBlocks
            .flatMap { block -> block.lines }
            .map { line ->
                OcrRegion(
                    text = line.text,
                    boundingBox = line.boundingBox?.let { rect -> RectF(rect) },
                    confidence = line.confidence.takeIf { it >= 0f },
                    languageTag = line.recognizedLanguage
                )
            }
        return OcrResult(regions = regions, fullText = text.text)
    }

    override fun close() {
        if (recognizersDelegate.isInitialized()) {
            recognizers.forEach { recognizer -> recognizer.close() }
        }
    }
}
