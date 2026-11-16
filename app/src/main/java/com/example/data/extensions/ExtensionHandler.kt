package com.example.data.extensions

import com.example.data.extensions.otips.OTipsError
import java.io.File

/**
 * Installs, and later removes, one [ExtensionKinds] kind of package.
 *
 * The repository does everything common to all kinds — reading and verifying
 * the `.OTips` file, unpacking it into a staging folder, swapping folders
 * atomically, keeping the extension's saved data across updates. A handler only
 * adds what is specific to its kind: checking that the content makes sense, and
 * loading it into the app when the kind is shown natively rather than in a
 * WebView.
 */
interface ExtensionHandler {
    val kind: String

    /** Ceiling on the packed payload, checked from the header before anything is unpacked. */
    val maxPayloadBytes: Long

    /**
     * Whether the unpacked content stays on disk once installed. True when the
     * app reads it at run time (an HTML page); false when [apply] copies it
     * somewhere else (a database), so the phone does not keep it twice.
     */
    val keepsContent: Boolean get() = true

    /** Throws [ExtensionInstallException] when [content] cannot work as [manifest] describes. */
    fun validate(manifest: ExtensionManifest, content: File)

    /**
     * Makes the package live. Runs while [content] is still in staging and the
     * previous version is still installed, so a failure here leaves the phone
     * exactly as it was.
     */
    suspend fun apply(extension: OrthoExtension, content: File, previous: OrthoExtension?) = Unit

    /** Undoes [apply]. Runs before the extension's folder is deleted. */
    suspend fun remove(extension: OrthoExtension) = Unit
}

/** The kinds this build of the app can install. */
class ExtensionHandlerRegistry(handlers: Iterable<ExtensionHandler>) {
    private val byKind: Map<String, ExtensionHandler> = handlers.associateBy { it.kind }

    operator fun get(kind: String): ExtensionHandler? = byKind[kind]

    fun supports(kind: String): Boolean = kind in byKind
}

/** Why a package could not be inspected, installed or produced; each has its own message. */
enum class ExtensionError {
    UNREADABLE,
    NOT_A_PACKAGE,
    CORRUPTED,
    UNSUPPORTED_FORMAT,
    OUTDATED_PACKAGE,
    UNSUPPORTED_KIND,
    REQUIRES_NEWER_APP,
    INVALID_MANIFEST,
    INVALID_CONTENT,
    NOT_HTML,
    TOO_LARGE,
    INSTALL_FAILED,
    STORAGE_FAILED;

    companion object {
        fun from(error: OTipsError): ExtensionError = when (error) {
            OTipsError.NOT_A_PACKAGE -> NOT_A_PACKAGE
            OTipsError.CORRUPTED -> CORRUPTED
            OTipsError.UNSUPPORTED_FORMAT -> UNSUPPORTED_FORMAT
            OTipsError.OUTDATED -> OUTDATED_PACKAGE
            OTipsError.INVALID_MANIFEST -> INVALID_MANIFEST
            OTipsError.TOO_LARGE -> TOO_LARGE
            OTipsError.IO -> UNREADABLE
        }
    }
}

class ExtensionInstallException(val error: ExtensionError, message: String, cause: Throwable? = null) :
    Exception(message, cause)
