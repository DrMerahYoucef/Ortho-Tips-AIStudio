package com.example.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads when a photo or video was actually taken, instead of when it was added to
 * a patient: EXIF for images, the container metadata for videos, then the file's
 * own date, then now.
 */
object MediaMetadata {

    private val EXIF_FORMAT = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
    private val VIDEO_FORMAT = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)

    fun isVideo(context: Context, uri: Uri): Boolean {
        val type = context.contentResolver.getType(uri)
        if (type != null) return type.startsWith("video/")
        return uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() in VIDEO_EXTENSIONS
    }

    /** Best known capture time for [uri], in milliseconds. */
    fun capturedAt(context: Context, uri: Uri): Long =
        (if (isVideo(context, uri)) videoDate(context, uri) else imageDate(context, uri))
            ?: contentDate(context, uri)
            ?: System.currentTimeMillis()

    fun capturedAt(file: File): Long =
        runCatching { ExifInterface(file.absolutePath) }.getOrNull()?.let(::exifDate)
            ?: file.lastModified().takeIf { it > 0 }
            ?: System.currentTimeMillis()

    private fun imageDate(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { exifDate(ExifInterface(it)) }
    }.getOrNull()

    private fun exifDate(exif: ExifInterface): Long? {
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null
        return runCatching { EXIF_FORMAT.parse(raw)?.time }.getOrNull()
    }

    private fun videoDate(context: Context, uri: Uri): Long? = withRetriever(context, uri) { retriever ->
        // e.g. "20260917T143012.000Z" — the "Z" suffix is not always accurate,
        // but the date and time are what matter here.
        val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
        raw?.let { VIDEO_FORMAT.parse(it.take(15))?.time }
    }

    /** The provider's own "last modified", which gallery pickers usually expose. */
    private fun contentDate(context: Context, uri: Uri): Long? = runCatching {
        if (uri.scheme == "file") return uri.path?.let { File(it).lastModified() }?.takeIf { it > 0 }
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex("last_modified")
                .takeIf { it >= 0 } ?: cursor.getColumnIndex("datetaken")
            if (index < 0 || !cursor.moveToFirst()) return null
            val value = cursor.getLong(index)
            // Some providers report seconds, others milliseconds.
            when {
                value <= 0 -> null
                value < 100_000_000_000L -> value * 1000
                else -> value
            }
        }
    }.getOrNull()

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    /** Video length in milliseconds, or null when it cannot be read. */
    fun durationMs(context: Context, uri: Uri): Long? = withRetriever(context, uri) { retriever ->
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    }

    /** MediaMetadataRetriever only became Closeable in Android 10, hence this. */
    private fun <T> withRetriever(context: Context, uri: Uri, block: (MediaMetadataRetriever) -> T?): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            block(retriever)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** First frame of a video, used as its tile in the media grid. */
    fun videoThumbnail(context: Context, uri: Uri): android.graphics.Bitmap? =
        withRetriever(context, uri) { it.frameAtTime }

    fun formatDate(timestamp: Long, locale: Locale): String =
        SimpleDateFormat("d MMM yyyy · HH:mm", locale).format(Date(timestamp))

    private val VIDEO_EXTENSIONS = setOf("mp4", "3gp", "mkv", "webm", "mov", "avi")
}
