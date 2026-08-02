package com.anezium.rokidbus.phone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

internal data class NormalizedSnapshot(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val quality: Int,
)

/**
 * Decodes firmware-dependent camera output (including WebP), then always emits a bounded JPEG.
 */
internal object SnapshotJpegEncoder {
    fun normalize(
        encoded: ByteArray,
        maxBytes: Int,
        jpegQuality: Int = 80,
    ): NormalizedSnapshot {
        require(encoded.isNotEmpty()) { "Encoded snapshot is empty." }
        require(maxBytes > 0) { "Snapshot byte cap must be positive." }
        require(jpegQuality in 1..100) { "JPEG quality must be between 1 and 100." }

        var bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
            ?: throw IllegalStateException("Glasses photo could not be decoded.")
        try {
            repeat(MAX_DOWNSCALE_PASSES) {
                val jpeg = bitmap.toJpeg(jpegQuality)
                if (jpeg.size <= maxBytes) {
                    return NormalizedSnapshot(
                        jpeg = jpeg,
                        width = bitmap.width,
                        height = bitmap.height,
                        quality = jpegQuality,
                    )
                }

                val ratio = sqrt(maxBytes.toDouble() / jpeg.size.toDouble()) * SCALE_HEADROOM
                val scale = min(MAX_SCALE_STEP, ratio).coerceIn(MIN_SCALE_STEP, MAX_SCALE_STEP)
                val width = floor(bitmap.width * scale).toInt().coerceAtLeast(1)
                val height = floor(bitmap.height * scale).toInt().coerceAtLeast(1)
                if (width == bitmap.width && height == bitmap.height) return@repeat
                val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                if (scaled !== bitmap) {
                    bitmap.recycle()
                    bitmap = scaled
                }
            }

            for (quality in FALLBACK_QUALITIES) {
                val jpeg = bitmap.toJpeg(quality)
                if (jpeg.size <= maxBytes) {
                    return NormalizedSnapshot(jpeg, bitmap.width, bitmap.height, quality)
                }
            }
            throw IllegalStateException("Glasses photo could not fit the plugin binary limit.")
        } finally {
            bitmap.recycle()
        }
    }

    private fun Bitmap.toJpeg(quality: Int): ByteArray {
        val output = ByteArrayOutputStream()
        if (!compress(Bitmap.CompressFormat.JPEG, quality, output)) {
            throw IllegalStateException("Glasses photo JPEG encoding failed.")
        }
        return output.toByteArray()
    }

    private const val MAX_DOWNSCALE_PASSES = 16
    private const val SCALE_HEADROOM = 0.92
    private const val MAX_SCALE_STEP = 0.85
    private const val MIN_SCALE_STEP = 0.10
    private val FALLBACK_QUALITIES = intArrayOf(70, 60, 50, 40, 30, 20, 10)
}
