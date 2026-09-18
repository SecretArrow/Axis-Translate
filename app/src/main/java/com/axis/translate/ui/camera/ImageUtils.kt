package com.axis.translate.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.InputStream

/**
 * Bitmap helpers for the camera flow (SPEC #12): bounds-first downsampled
 * decoding and EXIF-aware rotation. Everything is null-safe so a corrupt or
 * unreadable image degrades to an error message instead of a crash.
 */
internal object ImageUtils {

    /** Decodes [file] downsampled so its longest edge is at most [maxDimension]. */
    fun decodeDownsampled(file: File, maxDimension: Int = 2048): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    /**
     * Decodes [uri] downsampled so its longest edge is at most [maxDimension].
     * Opens the stream once for bounds, once for the real decode and once for
     * the EXIF orientation, which is applied so the result is upright.
     */
    fun decodeDownsampled(context: Context, uri: Uri, maxDimension: Int = 2048): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return@runCatching null
        // BitmapFactory ignores EXIF orientation: gallery/camera photos would
        // otherwise be decoded (and OCR'd) sideways.
        val rotation = context.contentResolver.openInputStream(uri)?.use(::readExifRotation) ?: 0
        rotateBitmap(bitmap, rotation)
    }.getOrNull()

    /**
     * Returns [bitmap] rotated by [degrees], or [bitmap] itself when the
     * rotation is a multiple of 360. The source bitmap is recycled only when
     * a new instance was allocated (createBitmap with a non-identity matrix
     * always copies pixels, so the original is safe to release then).
     */
    fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val rotation = ((degrees % 360) + 360) % 360
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /** Reads the EXIF orientation of [file] as a clockwise rotation of 0/90/180/270. */
    fun readExifRotation(file: File): Int = runCatching {
        orientationToDegrees(
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        )
    }.getOrDefault(0)

    /** Reads the EXIF orientation of a fresh [stream] as a clockwise rotation of 0/90/180/270. */
    private fun readExifRotation(stream: InputStream): Int = runCatching {
        orientationToDegrees(
            ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        )
    }.getOrDefault(0)

    private fun orientationToDegrees(orientation: Int): Int = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
        else -> 0
    }

    /** Largest power-of-two sample size keeping the decoded longest edge within [maxDimension]. */
    private fun computeInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sampleSize = 1
        while (maxOf(width, height) / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
