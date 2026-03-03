package com.nova.companion.vision

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Utility for converting Bitmaps to base64-encoded JPEG strings
 * for GPT-4o vision API calls.
 *
 * Security: All operations are in-memory. No bitmaps or encoded
 * data are written to disk or logged.
 */
object BitmapEncoder {

    private const val MAX_DIMENSION = 1024
    private const val JPEG_QUALITY = 60

    /**
     * Encode a Bitmap to a base64 JPEG string suitable for the OpenAI vision API.
     *
     * - Scales down to max 1024px on longest dimension
     * - Compresses at 60% JPEG quality for cost efficiency
     * - Clears intermediate buffers after encoding
     *
     * @param bitmap Source bitmap (NOT recycled by this method — caller manages lifecycle)
     * @return Base64-encoded JPEG string (no line breaks)
     */
    fun encodeToBase64(bitmap: Bitmap): String {
        val scaled = scaleDown(bitmap, MAX_DIMENSION)
        val baos = ByteArrayOutputStream()

        try {
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
            val bytes = baos.toByteArray()
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally {
            baos.reset()
            // Recycle the scaled copy if it's different from the original
            if (scaled !== bitmap) {
                scaled.recycle()
            }
        }
    }

    /**
     * Scale bitmap so the longest dimension is at most [maxDimension] pixels.
     * Returns the same bitmap instance if already within bounds.
     */
    fun scaleDown(bitmap: Bitmap, maxDimension: Int = MAX_DIMENSION): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val longest = maxOf(width, height)

        if (longest <= maxDimension) return bitmap

        val scale = maxDimension.toFloat() / longest
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
