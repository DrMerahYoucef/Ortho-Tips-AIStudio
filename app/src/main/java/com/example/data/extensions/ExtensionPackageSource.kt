package com.example.data.extensions

import android.content.Context
import android.net.Uri
import java.io.FileNotFoundException
import java.io.InputStream

/** Where an `.OTips` package is read from. Opened more than once: header first, then content. */
sealed interface ExtensionPackageSource {

    val origin: ExtensionSource

    fun open(context: Context): InputStream

    /** A file shipped in `assets/` [BundledExtensions.ASSET_DIR]. */
    data class Bundled(val assetPath: String) : ExtensionPackageSource {
        override val origin: ExtensionSource get() = ExtensionSource.BUNDLED
        override fun open(context: Context): InputStream = context.assets.open(assetPath)
    }

    /** A package the app itself produced, e.g. a pack published from the classification editor. */
    data class LocalFile(val file: java.io.File) : ExtensionPackageSource {
        override val origin: ExtensionSource get() = ExtensionSource.EXTERNAL
        override fun open(context: Context): InputStream = file.inputStream()
    }

    /** A document the user picked through the system file picker. */
    data class Document(val uri: Uri) : ExtensionPackageSource {
        override val origin: ExtensionSource get() = ExtensionSource.EXTERNAL
        override fun open(context: Context): InputStream =
            context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
    }
}

/** Packages shipped with the app, found by listing their asset folder. */
object BundledExtensions {
    const val ASSET_DIR = "bundled_extensions"
}
