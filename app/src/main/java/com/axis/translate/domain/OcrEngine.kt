package com.axis.translate.domain

import android.graphics.Bitmap
import android.graphics.RectF

/** One recognized text region (SPEC #11). */
data class OcrRegion(
    val text: String,
    val boundingBox: RectF? = null,
    val confidence: Float? = null,
    val languageTag: String? = null,
    val lineCount: Int = 1
)

/** Result of offline OCR over a whole image. */
data class OcrResult(
    val regions: List<OcrRegion> = emptyList(),
    val fullText: String = ""
) {
    val isEmpty: Boolean get() = fullText.isBlank() && regions.isEmpty()
    val hasLowConfidence: Boolean
        get() = regions.any { (it.confidence ?: 1f) < LOW_CONFIDENCE }

    companion object {
        const val LOW_CONFIDENCE = 0.6f
    }
}

/**
 * Fully offline OCR engine contract (SPEC #9) — implemented on top of
 * ML Kit on-device text recognition; must never touch a remote API.
 */
interface OcrEngine {
    /**
     * Recognizes text in [image]. [rotationDegrees] is the source rotation
     * to apply (from EXIF or CameraX metadata).
     */
    suspend fun recognize(image: Bitmap, rotationDegrees: Int = 0): Result<OcrResult>

    /** Release native resources. */
    fun close()
}
