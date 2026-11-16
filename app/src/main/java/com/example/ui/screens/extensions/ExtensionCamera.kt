package com.example.ui.screens.extensions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.utils.ShareHelper
import java.io.File

/** What a page's `<input type="file">` asked for. */
internal data class FileRequest(val mimeTypes: List<String>, val camera: Boolean)

/**
 * Photo capture for extension file inputs: `<input type="file" accept="image/…"
 * capture="environment">` opens the camera instead of the file picker (extension
 * guide, section 5). Photos go to the cache folder `extension_camera`, shared through
 * the app's FileProvider, and are cleaned up after a day.
 */
internal object ExtensionCamera {

    private const val TAG = "ExtensionCamera"
    const val DIR = "extension_camera"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** The camera when `capture` is set and every accepted type is an image (or none is given). */
    fun wantsCamera(captureEnabled: Boolean, acceptTypes: Array<String>?): Boolean {
        if (!captureEnabled) return false
        val types = acceptTypes.orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        return types.all { it.startsWith("image/") }
    }

    /** A new `.jpg` file, so the WebView hands the page `image/jpeg`. */
    fun newCaptureFile(context: Context): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        return File(dir, "${System.currentTimeMillis()}.jpg")
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, ShareHelper.providerAuthority(context), file)

    /**
     * True when the app's manifest declares CAMERA and it is not granted yet:
     * Android then refuses ACTION_IMAGE_CAPTURE until it is.
     */
    fun needsCameraPermission(context: Context): Boolean =
        declares(context, Manifest.permission.CAMERA) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun declares(context: Context, permission: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(permission) == true
    }.getOrDefault(true)

    /** Photos older than a day (taken, then abandoned or already read by the page). */
    fun cleanOldCaptures(context: Context, now: Long = System.currentTimeMillis()) {
        try {
            File(context.cacheDir, DIR).listFiles()?.forEach { if (now - it.lastModified() > MAX_AGE_MS) it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not clean old captures", e)
        }
    }
}
