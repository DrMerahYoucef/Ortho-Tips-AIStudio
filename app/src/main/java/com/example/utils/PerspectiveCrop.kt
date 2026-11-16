package com.example.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.hypot
import kotlin.math.max

/**
 * Warps an arbitrary 4-corner quadrilateral selected on a photographed document (e.g. a
 * page held at an angle) into a flat, deskewed rectangle — the same technique document
 * scanner apps use. Built entirely on Android's own [Matrix.setPolyToPoly]; no external
 * vision library needed.
 */
object PerspectiveCrop {

    /**
     * [corners] must be exactly 4 points in **source bitmap pixel space**, ordered
     * clockwise starting from the top-left: top-left, top-right, bottom-right, bottom-left.
     * The output size is derived from the quad's own edge lengths, so a quad photographed
     * at an angle still produces a correctly-proportioned rectangle.
     */
    fun warp(source: Bitmap, corners: List<PointF>): Bitmap {
        require(corners.size == 4) { "Expected exactly 4 corners, got ${corners.size}" }
        val (topLeft, topRight, bottomRight, bottomLeft) = corners

        val outWidth = max(distance(topLeft, topRight), distance(bottomLeft, bottomRight))
            .toInt().coerceAtLeast(1)
        val outHeight = max(distance(topLeft, bottomLeft), distance(topRight, bottomRight))
            .toInt().coerceAtLeast(1)

        val src = floatArrayOf(
            topLeft.x, topLeft.y,
            topRight.x, topRight.y,
            bottomRight.x, bottomRight.y,
            bottomLeft.x, bottomLeft.y
        )
        val dst = floatArrayOf(
            0f, 0f,
            outWidth.toFloat(), 0f,
            outWidth.toFloat(), outHeight.toFloat(),
            0f, outHeight.toFloat()
        )

        val matrix = Matrix()
        matrix.setPolyToPoly(src, 0, dst, 0, 4)

        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, matrix, paint)
        return output
    }

    private fun distance(a: PointF, b: PointF): Float =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
}
