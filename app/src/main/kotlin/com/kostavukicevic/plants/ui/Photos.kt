// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.datetime.LocalDate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Above this a photo is downscaled before it is stored; below it is kept byte for byte. */
private const val MAX_STORED_BYTES = 4 * 1024 * 1024
private const val DOWNSCALED_LONG_SIDE_PX = 3000

/** The camera app writes here, through the FileProvider declared in the manifest; the photo is then copied into the photo directory. */
fun cameraTarget(context: Context): Pair<File, Uri> {
    val file = File(context.cacheDir, "camera/capture.jpg")
    file.parentFile?.mkdirs()
    file.delete()
    return file to FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
}

/** The day the photo was taken, from its EXIF data, if it has one. */
fun takenOn(bytes: ByteArray): LocalDate? {
    val exif = exif(ByteArrayInputStream(bytes)) ?: return null
    val stamp = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
    // "yyyy:MM:dd HH:mm:ss"; a malformed stamp means the day is unknown and the caller uses today.
    return try {
        LocalDate.parse(stamp.take(10).replace(':', '-'))
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** EXIF is optional: an image without readable EXIF has no date and no rotation. */
private fun exif(input: InputStream): ExifInterface? = try {
    ExifInterface(input)
} catch (_: IOException) {
    null
}

/**
 * The bytes to store: unchanged up to [MAX_STORED_BYTES], else a JPEG with its long side at most
 * [DOWNSCALED_LONG_SIDE_PX], turned upright (re-encoding drops the EXIF orientation).
 * Can throw [OutOfMemoryError] on a huge image; the caller reports it.
 */
fun forStorage(bytes: ByteArray): ByteArray {
    if (bytes.size <= MAX_STORED_BYTES) return bytes
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return bytes // not decodable here: keep it as it is
    val options = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds, DOWNSCALED_LONG_SIDE_PX) }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return bytes
    val upright = fit(decoded, DOWNSCALED_LONG_SIDE_PX, orientation(ByteArrayInputStream(bytes)))
    return ByteArrayOutputStream().also { upright.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
}

/** A small upright bitmap of the stored photo, its long side at most [longSidePx]; null if the file is gone or unreadable. */
fun thumbnail(file: File, longSidePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleFor(bounds, longSidePx)
        inPreferredConfig = Bitmap.Config.RGB_565 // half the memory; photos have no transparency
    }
    val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null
    return fit(decoded, longSidePx, file.inputStream().use { orientation(it) })
}

/** The largest power of two that keeps the decoded long side at or above [longSidePx]: the cheap part of the scaling. */
private fun sampleFor(bounds: BitmapFactory.Options, longSidePx: Int): Int {
    val longSide = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (longSide / (sample * 2) >= longSidePx) sample *= 2
    return sample
}

private fun orientation(input: InputStream): Int =
    exif(input)?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) ?: ExifInterface.ORIENTATION_NORMAL

/** Scales down to [longSidePx] and rotates upright in one pass, so at most one more bitmap is made; the input is recycled. */
private fun fit(bitmap: Bitmap, longSidePx: Int, orientation: Int): Bitmap {
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    val scale = minOf(1f, longSidePx.toFloat() / maxOf(bitmap.width, bitmap.height))
    if (degrees == 0f && scale == 1f) return bitmap
    val matrix = Matrix().apply { postScale(scale, scale); postRotate(degrees) }
    val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (result !== bitmap) bitmap.recycle()
    return result
}
