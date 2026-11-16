package com.example.utils

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.StringRes
import com.example.R
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/** Outcome of a signature cleanup pass — lets the UI tell the surgeon when a retake would help. */
sealed class SignatureResult {
    data class Success(val file: File) : SignatureResult()

    /** Cleaned up and saved, but the source photo made it hard to be sure — still worth a look. */
    data class Warning(val file: File, @StringRes val messageRes: Int) : SignatureResult()

    /** Nothing usable was written. */
    data class Failure(@StringRes val messageRes: Int) : SignatureResult()
}

/**
 * Turns a photographed or scanned signature (ink on white/off-white paper) into a
 * clean, transparent-background PNG ready to stamp onto PDF exports.
 *
 * Deliberately simple and binary: every pixel is either kept exactly as photographed
 * (ink) or made fully transparent (paper) — no partial/feathered alpha. Partially
 * transparent pixels require correctly-premultiplied colour values, and some PDF/PNG
 * viewers render that edge case as a dark halo — a full black background if enough
 * pixels are affected. A hard cutoff sidesteps that entirely, and it's also just a
 * closer match to "remove the white, keep the signature exactly as it is".
 *
 * The cutoff itself is computed automatically per photo with Otsu's method on the
 * grayscale histogram, rather than a fixed brightness number — that's what makes it
 * adapt to a photo's actual lighting instead of assuming a specific "white" value.
 */
object SignatureProcessor {

    private const val MAX_DIMENSION_PX = 1400
    private const val CROP_PADDING_PX = 14
    // Otsu finds the statistically optimal split, but it can sit a little too close to
    // the ink side on a low-contrast photo; nudging it a bit brighter keeps thin/light
    // pen strokes from being cut away.
    private const val THRESHOLD_BIAS = 12

    fun processFromUri(contentResolver: ContentResolver, uri: Uri, destination: File): SignatureResult {
        val source = runCatching { decodeBounded(contentResolver, uri) }.getOrNull()
            ?: return SignatureResult.Failure(R.string.signature_error_unreadable)
        return processBitmap(source, destination)
    }

    private fun processBitmap(source: Bitmap, destination: File): SignatureResult {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        source.recycle()

        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = width * height
        val pixels = IntArray(pixelCount)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // --- 1. Luminance + 256-bin histogram in one pass ---
        val luminance = IntArray(pixelCount)
        val histogram = IntArray(256)
        for (i in 0 until pixelCount) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val l = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            luminance[i] = l
            histogram[l]++
        }

        // --- 2. Otsu's method: pick the brightness that best splits paper from ink ---
        val threshold = (otsuThreshold(histogram, pixelCount) + THRESHOLD_BIAS).coerceIn(0, 255)

        // --- 3. Binary cutout: keep ink pixels exactly as-is, zero out everything else ---
        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0
        var inkPixelCount = 0L

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (luminance[idx] < threshold) {
                    // Ink — keep the original color untouched, fully opaque.
                    inkPixelCount++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    val p = pixels[idx]
                    pixels[idx] = (0xFF shl 24) or (p and 0x00FFFFFF)
                } else {
                    // Paper — fully transparent.
                    pixels[idx] = 0
                }
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        // Root cause of the black background: the source photo is a JPEG, which has no
        // alpha channel — so even after copying to ARGB_8888, Android's Bitmap keeps an
        // internal "this bitmap is fully opaque" flag from the original. That flag is an
        // optimization hint Skia's PNG encoder trusts *instead of* re-scanning every pixel,
        // so despite writing real alpha=0 values above, compress() was silently encoding an
        // opaque RGB-only PNG — every "transparent" pixel got baked in as solid black.
        // Must be set on this still-mutable bitmap; the cropped sub-bitmap below is immutable.
        bitmap.setHasAlpha(true)

        val inkFraction = inkPixelCount.toDouble() / pixelCount.toDouble()
        val hasInk = maxX > minX && maxY > minY

        if (!hasInk || inkFraction < 0.0015) {
            bitmap.recycle()
            return SignatureResult.Failure(R.string.signature_error_not_found)
        }

        val cropped = run {
            val left = (minX - CROP_PADDING_PX).coerceAtLeast(0)
            val top = (minY - CROP_PADDING_PX).coerceAtLeast(0)
            val right = (maxX + CROP_PADDING_PX).coerceAtMost(width - 1)
            val bottom = (maxY + CROP_PADDING_PX).coerceAtMost(height - 1)
            Bitmap.createBitmap(
                bitmap,
                left,
                top,
                (right - left).coerceAtLeast(1),
                (bottom - top).coerceAtLeast(1)
            )
        }

        destination.parentFile?.mkdirs()
        FileOutputStream(destination).use { out ->
            cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        if (cropped !== bitmap) bitmap.recycle()
        cropped.recycle()

        // Too much of the frame reads as "ink" — usually heavy shadow rather than a clean
        // background removal. Still saved (better than nothing) but flagged so the surgeon
        // knows to check it and can retake if it looks wrong.
        return if (inkFraction > 0.35) {
            SignatureResult.Warning(
                destination,
                R.string.signature_warning_lighting
            )
        } else {
            SignatureResult.Success(destination)
        }
    }

    /** Standard Otsu's method: the brightness cutoff that maximizes between-class variance. */
    private fun otsuThreshold(histogram: IntArray, totalPixels: Int): Int {
        if (totalPixels == 0) return 128

        var sumAll = 0L
        for (i in 0 until 256) sumAll += i.toLong() * histogram[i]

        var sumBackground = 0L
        var weightBackground = 0L
        var bestThreshold = 128
        var bestVariance = -1.0

        for (t in 0 until 256) {
            weightBackground += histogram[t]
            if (weightBackground == 0L) continue

            val weightForeground = totalPixels - weightBackground
            if (weightForeground == 0L) break

            sumBackground += t.toLong() * histogram[t]
            val meanBackground = sumBackground.toDouble() / weightBackground
            val meanForeground = (sumAll - sumBackground).toDouble() / weightForeground

            val variance = weightBackground.toDouble() * weightForeground.toDouble() *
                (meanBackground - meanForeground) * (meanBackground - meanForeground)

            if (variance > bestVariance) {
                bestVariance = variance
                bestThreshold = t
            }
        }
        return bestThreshold
    }

    private fun decodeBounded(contentResolver: ContentResolver, uri: Uri): Bitmap {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOptions) }

        var sampleSize = 1
        val largestSide = max(boundsOptions.outWidth, boundsOptions.outHeight)
        while (largestSide / sampleSize > MAX_DIMENSION_PX * 2) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: error("Unable to decode signature image at $uri")
    }
}
