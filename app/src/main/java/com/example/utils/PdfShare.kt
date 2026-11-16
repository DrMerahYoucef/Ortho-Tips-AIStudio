package com.example.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Shares a generated file (operative protocol, patient export, etc.) via a
 * FileProvider content:// Uri. Requires a <provider> entry in AndroidManifest.xml —
 * see the authority constant below.
 */
object ShareHelper {

    /** Must match the authority declared for the FileProvider in AndroidManifest.xml. */
    fun providerAuthority(context: Context): String = "${context.packageName}.fileprovider"

    fun sharePdf(context: Context, file: File, chooserTitle: String = "Share PDF") {
        shareFile(context, file, mimeType = "application/pdf", chooserTitle = chooserTitle)
    }

    fun shareFile(context: Context, file: File, mimeType: String, chooserTitle: String = "Share file") {
        val uri = FileProvider.getUriForFile(context, providerAuthority(context), file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }

    /** Same as [shareFile] but for sending several files (e.g. a multi-select from Media) at once. */
    fun shareFiles(context: Context, files: List<File>, mimeType: String, chooserTitle: String = "Share files") {
        if (files.isEmpty()) return
        if (files.size == 1) {
            shareFile(context, files.first(), mimeType, chooserTitle)
            return
        }
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, providerAuthority(context), it) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }
}
