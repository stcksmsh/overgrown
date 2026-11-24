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

/** The bytes to store: unchanged up to [MAX_STORED_BYTES], else a smaller JPEG turned upright (re-encoding drops the EXIF orientation). */
fun forStorage(bytes: ByteArray): ByteArray {
    if (bytes.size <= MAX_STORED_BYTES) return bytes
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longSide = maxOf(bounds.outWidth, bounds.outHeight)
    if (longSide <= 0) return bytes // not decodable here: keep it as it is
    var sample = 1
    while (longSide / (sample * 2) >= DOWNSCALED_LONG_SIDE_PX) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return bytes
    val upright = rotate(bitmap, orientation(ByteArrayInputStream(bytes)))
    return ByteArrayOutputStream().also { upright.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
}

/** A bitmap of the stored photo about [widthPx] wide, upright. */
fun thumbnail(file: File, widthPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= widthPx) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return rotate(bitmap, file.inputStream().use { orientation(it) })
}

private fun orientation(input: InputStream): Int =
    exif(input)?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) ?: ExifInterface.ORIENTATION_NORMAL

private fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
}
