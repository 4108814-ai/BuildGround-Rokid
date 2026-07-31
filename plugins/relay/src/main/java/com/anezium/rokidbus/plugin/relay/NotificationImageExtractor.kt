package com.anezium.rokidbus.plugin.relay

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import com.anezium.rokidbus.shared.ImageSurfaceContract
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

internal data class NotificationImagePreview(
    val id: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
    val source: String,
)

internal data class ImageEncodingAttempt(val longestEdge: Int, val jpegQuality: Int)

internal data class ImageEncodingCandidate<T>(
    val value: T,
    val width: Int,
    val height: Int,
    val byteSize: Int,
)

internal object ImageRetryPolicy {
    val attempts: List<ImageEncodingAttempt> = buildList {
        for (edge in intArrayOf(512, 384, 256)) {
            for (quality in intArrayOf(68, 58, 48)) add(ImageEncodingAttempt(edge, quality))
        }
    }

    fun <T> firstFitting(
        encode: (ImageEncodingAttempt) -> ImageEncodingCandidate<T>?,
    ): ImageEncodingCandidate<T>? {
        for (attempt in attempts) {
            val candidate = encode(attempt) ?: continue
            if (candidate.byteSize !in 1..ImageSurfaceContract.MAX_IMAGE_BYTES) continue
            if (candidate.width !in MIN_PREVIEW_EDGE_PX..ImageSurfaceContract.MAX_EDGE_PIXELS) continue
            if (candidate.height !in MIN_PREVIEW_EDGE_PX..ImageSurfaceContract.MAX_EDGE_PIXELS) continue
            if (candidate.width.toLong() * candidate.height > ImageSurfaceContract.MAX_TOTAL_PIXELS) continue
            return candidate
        }
        return null
    }

    const val MIN_PREVIEW_EDGE_PX = 24
}

internal object NotificationImageExtractor {
    private const val EXTRA_PICTURE_ICON = "android.pictureIcon"

    fun extract(context: Context, notification: Notification): NotificationImagePreview? {
        val extras = notification.extras ?: return null
        messagingStyleImage(context, extras)?.let { return it }
        return bigPictureImage(context, extras)
    }

    private fun messagingStyleImage(context: Context, extras: Bundle): NotificationImagePreview? {
        val bundles = messageBundles(extras) ?: return null
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
        return messages.asReversed().firstNotNullOfOrNull { message ->
            val mimeType = message.dataMimeType?.lowercase().orEmpty()
            val uri = message.dataUri
            if (!mimeType.startsWith("image/") || uri == null) null else {
                decodeUri(context, uri)?.let { bitmap -> encode(bitmap, "messaging_style") }
            }
        }
    }

    private fun bigPictureImage(context: Context, extras: Bundle): NotificationImagePreview? {
        val picture = extras.getNotificationParcelable(Notification.EXTRA_PICTURE)
        val bitmap = when (picture) {
            is Bitmap -> picture
            is Icon -> iconToBitmap(context, picture)
            is Drawable -> drawableToBitmap(picture)
            else -> null
        }
        if (bitmap != null) return encode(bitmap, "big_picture")

        val pictureIcon = extras.getNotificationParcelable(EXTRA_PICTURE_ICON) as? Icon
        return pictureIcon?.let { icon ->
            iconToBitmap(context, icon)?.let { bitmapFromIcon ->
                encode(bitmapFromIcon, "big_picture_icon")
            }
        }
    }

    private fun decodeUri(context: Context, uri: Uri): Bitmap? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (width, height) = targetSize(info.size.width, info.size.height)
            if (width > 0 && height > 0) decoder.setTargetSize(width, height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()

    private fun iconToBitmap(context: Context, icon: Icon): Bitmap? =
        runCatching { icon.loadDrawable(context) }.getOrNull()?.let(::drawableToBitmap)

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: ImageSurfaceContract.MAX_EDGE_PIXELS
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: ImageSurfaceContract.MAX_EDGE_PIXELS
        val (targetWidth, targetHeight) = targetSize(width, height)
        if (targetWidth <= 0 || targetHeight <= 0) return null
        return Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
    }

    internal fun encode(bitmap: Bitmap, source: String): NotificationImagePreview? {
        if (bitmap.width < ImageRetryPolicy.MIN_PREVIEW_EDGE_PX ||
            bitmap.height < ImageRetryPolicy.MIN_PREVIEW_EDGE_PX
        ) {
            return null
        }
        val software = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else {
            bitmap
        }
        val candidate = ImageRetryPolicy.firstFitting { attempt ->
            val scaled = software.scaledToLongEdge(attempt.longestEdge)
            val bytes = scaled.toJpeg(attempt.jpegQuality)
            ImageEncodingCandidate(
                value = bytes,
                width = scaled.width,
                height = scaled.height,
                byteSize = bytes.size,
            )
        } ?: return null
        return NotificationImagePreview(
            id = stableId(candidate.value),
            mimeType = ImageSurfaceContract.MIME_JPEG,
            width = candidate.width,
            height = candidate.height,
            bytes = candidate.value,
            source = source,
        )
    }

    private fun Bitmap.scaledToLongEdge(maxLongEdge: Int): Bitmap {
        val (targetWidth, targetHeight) = targetSize(width, height, maxLongEdge)
        if (targetWidth == width && targetHeight == height) return this
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }

    private fun Bitmap.toJpeg(quality: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            if (compress(Bitmap.CompressFormat.JPEG, quality, output)) output.toByteArray() else ByteArray(0)
        }

    private fun targetSize(
        width: Int,
        height: Int,
        maxLongEdge: Int = ImageSurfaceContract.MAX_EDGE_PIXELS,
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val longEdge = max(width, height)
        if (longEdge <= maxLongEdge) return width to height
        val scale = maxLongEdge.toFloat() / longEdge.toFloat()
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }

    private fun stableId(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .take(10)
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun messageBundles(extras: Bundle): Array<Parcelable>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        }

    @Suppress("DEPRECATION")
    private fun Bundle.getNotificationParcelable(key: String): Any? = get(key)
}
