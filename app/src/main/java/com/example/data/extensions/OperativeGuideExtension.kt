package com.example.data.extensions

import android.util.Log
import com.example.data.repository.OrthoRepository
import org.json.JSONObject
import java.io.File

/**
 * The operative guide as an extension.
 *
 * The package itself is small: it switches the guide on (the Operative Guides
 * tab, its search, bookmarks and notes) and describes the data it accepts. The
 * guide's content — chapters, procedures, sections and figures, often tens of
 * megabytes — is the user's own `bones_database.zip`, imported afterwards from
 * the guide's tab into the app's reference tables. It is never shipped in the
 * APK.
 *
 * Deleting the extension deletes that imported content too; bookmarks and case
 * notes are kept, exactly like the former "remove database" action, so
 * re-importing the same archive brings them back to their procedures.
 */
class OperativeGuideExtensionHandler(
    private val guideData: () -> OrthoRepository
) : ExtensionHandler {

    override val kind: String = ExtensionKinds.OPERATIVE_GUIDE

    override val maxPayloadBytes: Long = 1L * 1024 * 1024

    override fun validate(manifest: ExtensionManifest, content: File) {
        if (manifest.contentSchema > SUPPORTED_SCHEMA) {
            throw ExtensionInstallException(ExtensionError.UNSUPPORTED_FORMAT, "Guide schema ${manifest.contentSchema}")
        }
        val descriptor = File(content, DESCRIPTOR).takeIf { it.isFile }
            ?: throw ExtensionInstallException(ExtensionError.INVALID_CONTENT, "Missing $DESCRIPTOR")
        val format = runCatching { JSONObject(descriptor.readText(Charsets.UTF_8)).optString("dataFormat") }.getOrNull()
        if (format != DATA_FORMAT) {
            throw ExtensionInstallException(ExtensionError.INVALID_CONTENT, "Unsupported guide data format '$format'")
        }
    }

    override suspend fun remove(extension: OrthoExtension) {
        guideData().removeDatabase()
    }

    companion object {
        /** The id of the bundled operative guide. */
        const val ID = "operative-guide"
        const val DESCRIPTOR = "guide.json"

        /** The archive format [com.example.data.importer.BoneDatabaseImporter] reads. */
        const val DATA_FORMAT = "bones-zip"
        const val SUPPORTED_SCHEMA = 1
    }
}

/**
 * Brings installs from before the extension system into it, silently: a phone
 * that already holds operative guide data gets the bundled Operative Guide
 * extension installed, so the tab, bookmarks and notes carry on untouched and
 * nothing needs re-importing.
 *
 * Idempotent and cheap. It does nothing once the extension is installed, and
 * nothing when there is no guide data — which is also the state after the user
 * deletes the extension, so a deliberate removal is never undone.
 */
class OperativeGuideMigration(
    private val extensions: ExtensionRepository,
    private val guideData: OrthoRepository
) {
    suspend fun run() {
        if (extensions.get(OperativeGuideExtensionHandler.ID) != null) return
        if (guideData.getChapterCount() == 0) return
        val bundled = extensions.bundledPackages()
            .firstOrNull { it.manifest.id == OperativeGuideExtensionHandler.ID && it.installable }
        if (bundled == null) {
            Log.e(TAG, "Guide data present but the bundled Operative Guide package is missing")
            return
        }
        when (val result = extensions.install(bundled.source)) {
            is ExtensionRepository.InstallResult.Installed -> Log.i(TAG, "Existing operative guide data moved into its extension")
            is ExtensionRepository.InstallResult.Failed -> Log.e(TAG, "Operative guide migration failed: ${result.error}")
        }
    }

    private companion object {
        const val TAG = "OperativeGuideMigration"
    }
}
