package com.meetdheeran.prism.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Image plumbing shared by chat attachments, share targets and the screen tool. */
object Images {
    const val MAX_SIDE = 1280
    const val JPEG_QUALITY = 82

    /** Decodes [bytes], fixes EXIF rotation, downsizes so the long side <= [maxSide], re-encodes as JPEG. */
    fun toJpeg(bytes: ByteArray, maxSide: Int = MAX_SIDE, quality: Int = JPEG_QUALITY): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = sampleSize(max(bounds.outWidth, bounds.outHeight), maxSide)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val rotated = rotate(bmp, exifRotation(bytes))
        return encode(scale(rotated, maxSide), quality)
    }

    fun fromUri(ctx: Context, uri: Uri, maxSide: Int = MAX_SIDE): ByteArray? {
        val raw = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return null
        return toJpeg(raw, maxSide)
    }

    fun bitmapToJpeg(bmp: Bitmap, maxSide: Int = MAX_SIDE, quality: Int = JPEG_QUALITY): ByteArray = encode(scale(bmp, maxSide), quality)

    private fun sampleSize(longSide: Int, target: Int): Int {
        var s = 1
        while (longSide / (s * 2) >= target) s *= 2
        return s
    }

    private fun scale(bmp: Bitmap, maxSide: Int): Bitmap {
        val long = max(bmp.width, bmp.height)
        if (long <= maxSide) return bmp
        val f = maxSide.toFloat() / long
        return Bitmap.createScaledBitmap(bmp, (bmp.width * f).roundToInt().coerceAtLeast(1), (bmp.height * f).roundToInt().coerceAtLeast(1), true)
    }

    private fun encode(bmp: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    private fun exifRotation(bytes: ByteArray): Int = runCatching {
        when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun rotate(bmp: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bmp
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}
