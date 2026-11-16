package com.example.utils

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Shares an operative guide figure as an image, with its caption as text, so
 * it arrives in a messaging app as a picture rather than a bare description.
 *
 * Imported figures are shared straight from `filesDir/imported_figures/`; the
 * few figures shipped as drawables are copied to `cacheDir/shared_figures/`
 * first. Both folders are declared in `res/xml/file_paths.xml`. When the image
 * cannot be found the caption is still shared, as text.
 */
object FigureShare {

    private const val TAG = "FigureShare"
    private const val CACHE_DIR = "shared_figures"

    fun share(
        context: Context,
        imagePath: String?,
        drawableResName: String,
        title: String,
        caption: String,
        chooserTitle: String
    ) {
        val file = runCatching { resolveFile(context, imagePath, drawableResName) }
            .onFailure { Log.w(TAG, "Figure file unavailable", it) }
            .getOrNull()
        if (file == null) {
            MediaActions.shareClinicalTip(context, title, caption)
            return
        }
        val uri = FileProvider.getUriForFile(context, ShareHelper.providerAuthority(context), file)
        val text = listOf(title, caption).filter { it.isNotBlank() }.joinToString("\n\n")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
            // ClipData lets the chooser show a preview and carries the read grant.
            clipData = ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }

    private fun resolveFile(context: Context, imagePath: String?, drawableResName: String): File? {
        imagePath?.takeIf { it.isNotBlank() }?.let { path ->
            return File(path).takeIf { it.isFile }
        }
        if (drawableResName.isBlank()) return null
        @Suppress("DiscouragedApi")
        val resId = context.resources.getIdentifier(drawableResName, "drawable", context.packageName)
        if (resId == 0) return null
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val target = File(dir, "$drawableResName.jpg")
        if (!target.isFile) {
            context.resources.openRawResource(resId).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        return target
    }

    private fun mimeType(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?.takeIf { it.startsWith("image/") }
            ?: "image/*"
}
