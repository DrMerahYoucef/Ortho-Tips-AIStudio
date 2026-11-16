package com.example.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

/**
 * Copies a patient photo out of the app's private storage into the device's public
 * gallery, so the surgeon can access it from any gallery/photos app. Patient media
 * itself always stays in private storage (see [PatientMediaStorage]) — this only
 * creates a *copy*.
 */
object GallerySaver {

    private const val RELATIVE_DIR = "OrthoTips"

    sealed class Result {
        object Success : Result()
        object Failure : Result()
        /** Pre-Android-10 devices need WRITE_EXTERNAL_STORAGE granted first. */
        object NeedsPermission : Result()
    }

    fun saveToGallery(context: Context, file: File, displayName: String): Result {
        if (!file.exists()) return Result.Failure

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, file, displayName)
        } else {
            saveLegacy(context, file, displayName)
        }
    }

    private fun saveViaMediaStore(context: Context, file: File, displayName: String): Result {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$RELATIVE_DIR")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return Result.Failure
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { input -> input.copyTo(out) }
            } ?: return Result.Failure
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Result.Success
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            Result.Failure
        }
    }

    /** [android.Manifest.permission.WRITE_EXTERNAL_STORAGE] must already be granted before calling this. */
    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, file: File, displayName: String): Result {
        return try {
            val picturesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), RELATIVE_DIR)
            picturesDir.mkdirs()
            val destination = File(picturesDir, displayName)
            file.copyTo(destination, overwrite = true)
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(destination.absolutePath),
                arrayOf("image/jpeg"),
                null
            )
            Result.Success
        } catch (_: Exception) {
            Result.Failure
        }
    }
}
