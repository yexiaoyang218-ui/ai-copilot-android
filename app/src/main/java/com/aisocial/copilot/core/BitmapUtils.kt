package com.aisocial.copilot.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Normalised (0..1) rectangle inside an image, used by the crop overlay. */
data class NormalizedRect(
    val left: Float = 0.05f,
    val top: Float = 0.1f,
    val right: Float = 0.95f,
    val bottom: Float = 0.9f,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val isValid: Boolean get() = width > 0.002f && height > 0.002f

    fun normalized(): NormalizedRect {
        val l = min(left, right)
        val r = max(left, right)
        val t = min(top, bottom)
        val b = max(top, bottom)
        return NormalizedRect(
            left = l.coerceIn(0f, 1f),
            top = t.coerceIn(0f, 1f),
            right = r.coerceIn(0f, 1f),
            bottom = b.coerceIn(0f, 1f),
        )
    }
}

object BitmapUtils {

    /** Decode a content uri into a software bitmap, honouring EXIF rotation, capped to [maxDim]. */
    fun decodeFromUri(context: Context, uri: Uri, maxDim: Int = 2048): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                    val longest = max(info.size.width, info.size.height)
                    if (longest > maxDim) {
                        val sample = ceil(longest.toFloat() / maxDim.toFloat()).toInt().coerceAtLeast(1)
                        decoder.setTargetSampleSize(sample)
                    }
                }
            } else {
                decodeLegacy(context, uri, maxDim)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeLegacy(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val rotation = context.contentResolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        if (rotation == 0f) return decoded
        val matrix = Matrix().apply { postRotate(rotation) }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    fun scaleToMax(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / longest.toFloat()
        val out = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
            (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
            true
        )
        return out
    }

    /** Crop using a normalised rect. Always returns a non-empty bitmap. */
    fun crop(bitmap: Bitmap, rect: NormalizedRect): Bitmap {
        val r = rect.normalized()
        val x = (r.left * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
        val y = (r.top * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
        val w = (r.width * bitmap.width).roundToInt().coerceIn(1, bitmap.width - x)
        val h = (r.height * bitmap.height).roundToInt().coerceIn(1, bitmap.height - y)
        return Bitmap.createBitmap(bitmap, x, y, w, h)
    }

    fun toJpegBase64(bitmap: Bitmap, maxDim: Int = 1280, quality: Int = 82): String {
        val scaled = scaleToMax(bitmap, maxDim)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== bitmap) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
