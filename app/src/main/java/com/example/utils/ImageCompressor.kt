package com.example.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * All patient photos are re-encoded through here before being written to
 * [com.example.utils.PatientMediaStorage] so a long capture session never balloons
 * memory: source bitmaps are decoded at a bounded sample size, downscaled to a sane
 * max dimension, corrected for EXIF rotation, and saved as JPEG.
 */
object ImageCompressor {

    private const val MAX_DIMENSION_PX = 2000
    private const val THUMBNAIL_DIMENSION_PX = 320
    private const val JPEG_QUALITY = 85
    private const val THUMBNAIL_QUALITY = 70

    /** Decodes [sourceUri], applies EXIF rotation, downsamples, and writes a JPEG to [destination]. */
    fun compressToFile(contentResolver: android.content.ContentResolver, sourceUri: Uri, destination: File) {
        val bitmap = decodeBounded(contentResolver, sourceUri, MAX_DIMENSION_PX)
        val rotated = applyExifRotation(contentResolver, sourceUri, bitmap)
        writeJpeg(rotated, destination, JPEG_QUALITY)
        if (rotated !== bitmap) bitmap.recycle()
        rotated.recycle()
    }

    fun writeThumbnail(sourceFile: File, destination: File) {
        val bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath) ?: return
        val scaled = scaleToMax(bitmap, THUMBNAIL_DIMENSION_PX)
        writeJpeg(scaled, destination, THUMBNAIL_QUALITY)
        if (scaled !== bitmap) bitmap.recycle()
        scaled.recycle()
    }

    fun rotateFile(file: File, degrees: Float) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        writeJpeg(rotated, file, JPEG_QUALITY)
        bitmap.recycle()
        rotated.recycle()
    }

    /** Crops to a normalized rect (0f..1f on both axes, relative to the image) and overwrites [file]. */
    fun cropFile(file: File, leftFrac: Float, topFrac: Float, rightFrac: Float, bottomFrac: Float) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
        val left = (leftFrac.coerceIn(0f, 1f) * bitmap.width).toInt()
        val top = (topFrac.coerceIn(0f, 1f) * bitmap.height).toInt()
        val right = (rightFrac.coerceIn(0f, 1f) * bitmap.width).toInt()
        val bottom = (bottomFrac.coerceIn(0f, 1f) * bitmap.height).toInt()
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
        writeJpeg(cropped, file, JPEG_QUALITY)
        bitmap.recycle()
        cropped.recycle()
    }

    /** Writes [bitmap] as a JPEG to [destination] — exposed for callers (like perspective
     *  crop) that produce their own bitmap outside this object's normal pipeline. */
    fun saveJpeg(bitmap: Bitmap, destination: File) {
        writeJpeg(bitmap, destination, JPEG_QUALITY)
    }

    private fun decodeBounded(contentResolver: android.content.ContentResolver, uri: Uri, maxDimension: Int): Bitmap {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOptions) }

        var sampleSize = 1
        val largestSide = max(boundsOptions.outWidth, boundsOptions.outHeight)
        while (largestSide / sampleSize > maxDimension * 2) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: error("Unable to decode image at $uri")

        return scaleToMax(decoded, maxDimension)
    }

    private fun scaleToMax(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largestSide = max(bitmap.width, bitmap.height)
        if (largestSide <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / largestSide
        val newWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return bitmap.scale(newWidth, newHeight)
    }

    private fun applyExifRotation(contentResolver: android.content.ContentResolver, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap

        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun writeJpeg(bitmap: Bitmap, destination: File, quality: Int) {
        FileOutputStream(destination).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
    }
}
