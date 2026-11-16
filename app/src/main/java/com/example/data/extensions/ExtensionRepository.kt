package com.example.data.extensions

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.WebStorage
import com.example.BuildConfig
import com.example.data.extensions.otips.OTipsException
import com.example.data.extensions.otips.OTipsFormat
import com.example.data.extensions.otips.OTipsHeader
import com.example.data.extensions.otips.OTipsReader
import com.example.data.extensions.otips.OTipsWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Installed extensions — the single source of truth for everything the app
 * shows from an `.OTips` package. One folder each under `filesDir/extensions/`:
 *
 *     <id>/install.json   manifest, origin, granted permissions, dates, payload hash
 *     <id>/content/       the unpacked package (kinds that keep their content)
 *     <id>/files/         what the extension wrote through OrthoTips.files
 *
 * Key/value data lives separately in [ExtensionStorage] and survives updates.
 * Nothing here touches patient data, and the drive sync never sees this folder.
 *
 * Installing is all-or-nothing: the package is verified and unpacked into a
 * staging folder, its handler applies it, and only then is the staging folder
 * swapped in. A corrupted file, a crash half-way or a full disk leave the
 * previous version exactly as it was.
 */
class ExtensionRepository(
    private val context: Context,
    private val handlers: ExtensionHandlerRegistry,
    private val appVersionCode: Int = BuildConfig.VERSION_CODE,
    private val appVersionName: String = BuildConfig.VERSION_NAME,
    /** Called with every new list of installed extensions (e.g. who may read bank SMS). */
    private val onExtensionsChanged: (List<OrthoExtension>) -> Unit = {},
    /**
     * An extension was deleted or its data cleared ([granted] null), or it was
     * installed or updated with [granted] permissions. Used to drop its reminders.
     */
    private val onExtensionEvent: (id: String, granted: Set<ExtensionPermission>?) -> Unit = { _, _ -> }
) {

    sealed interface InstallResult {
        data class Installed(val extension: OrthoExtension, val previousVersion: String?) : InstallResult
        data class Failed(val error: ExtensionError) : InstallResult
    }

    sealed interface InspectResult {
        data class Ready(val preview: PackagePreview) : InspectResult
        data class Failed(val error: ExtensionError) : InspectResult
    }

    sealed interface ConvertResult {
        data class Converted(val manifest: ExtensionManifest) : ConvertResult
        data class Failed(val error: ExtensionError) : ConvertResult
    }

    private val root: File get() = File(context.filesDir, DIR_NAME)
    private val mutex = Mutex()
    @Volatile private var migrated = false
    @Volatile private var bundledHeaders: List<Pair<String, OTipsHeader>>? = null

    private val _extensions = MutableStateFlow<List<OrthoExtension>>(emptyList())
    val extensions: StateFlow<List<OrthoExtension>> = _extensions.asStateFlow()

    /** Rescans the folder. The first call also migrates pre-`.OTips` installs, silently. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!migrated) {
                migrateLegacyInstalls()
                cleanLeftovers()
                migrated = true
            }
            publish(scan())
        }
    }

    /** Scans once if nothing has been scanned yet; cheap afterwards. */
    suspend fun ensureScanned() {
        if (!migrated) refresh()
    }

    suspend fun get(id: String): OrthoExtension? = withContext(Dispatchers.IO) {
        extensionDir(id)?.let(::describe)
    }

    /** The page as the runner loads it: the entry HTML with the host bridge in front. */
    suspend fun loadPage(id: String): String? = withContext(Dispatchers.IO) {
        val extension = get(id)?.takeIf { it.kind == ExtensionKinds.HTML } ?: return@withContext null
        val page = contentDir(id)?.let { File(it, extension.manifest.entry) }?.takeIf { it.isFile }
            ?: return@withContext null
        val html = runCatching { page.readText(Charsets.UTF_8) }.getOrNull() ?: return@withContext null
        val bridge = context.assets.open(BRIDGE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        injectBridge(html, bridge)
    }

    /** The unpacked package, read by `OrthoTips.assets` and by native kinds. */
    fun contentDir(id: String): File? = extensionDir(id)?.let { File(it, CONTENT_DIR) }?.takeIf { it.isDirectory }

    /** The extension's private folder for `OrthoTips.files`; created on first write. */
    fun privateFilesDir(id: String): File? = extensionDir(id)?.let { File(it, FILES_DIR) }

    // ---- packages ------------------------------------------------------------

    /** Packages shipped in the app, each compared with what is installed. */
    suspend fun bundledPackages(): List<PackagePreview> = withContext(Dispatchers.IO) {
        val installed = _extensions.value.associateBy { it.id }
        loadBundledHeaders().map { (path, header) ->
            preview(ExtensionPackageSource.Bundled(path), header, installed[header.manifest.id])
        }
    }

    /** Reads a package's header only: enough to describe it before installing. */
    suspend fun inspect(source: ExtensionPackageSource): InspectResult = withContext(Dispatchers.IO) {
        try {
            val header = source.open(context).use { OTipsReader.readHeader(it) }
            val installed = extensionDir(header.manifest.id)?.let(::describe)
            InspectResult.Ready(preview(source, header, installed))
        } catch (e: OTipsException) {
            InspectResult.Failed(ExtensionError.from(e.error))
        } catch (e: Exception) {
            Log.w(TAG, "Could not inspect $source", e)
            InspectResult.Failed(ExtensionError.UNREADABLE)
        }
    }

    /**
     * Verifies and installs a package. A package whose id is already installed
     * replaces it and keeps its saved data; that is how updates work.
     */
    suspend fun install(source: ExtensionPackageSource): InstallResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            root.mkdirs()
            val staging = File(root, STAGING_PREFIX + UUID.randomUUID())
            try {
                val announced = source.open(context).use { OTipsReader.readHeader(it) }
                val handler = checkInstallable(announced)

                val content = File(staging, CONTENT_DIR)
                val header = source.open(context).use {
                    OTipsReader.extract(it, content, maxPayloadBytes = handler.maxPayloadBytes)
                }
                if (header.manifest != announced.manifest) {
                    throw ExtensionInstallException(ExtensionError.CORRUPTED, "Package changed while reading")
                }
                val manifest = header.manifest
                handler.validate(manifest, content)

                val previous = extensionDir(manifest.id)?.let(::describe)
                val now = System.currentTimeMillis()
                val record = InstallRecord(
                    manifest = manifest,
                    source = source.origin,
                    granted = manifest.permissions,
                    installedAt = previous?.installedAt ?: now,
                    updatedAt = now,
                    payloadSha256 = header.payloadSha256
                )
                handler.apply(record.toExtension(sizeBytes = 0, exportable = false), content, previous)
                if (!handler.keepsContent) content.deleteRecursively()
                writeRecord(staging, record)
                commit(staging, File(root, manifest.id))
                runCatching { onExtensionEvent(manifest.id, manifest.permissions) }
                    .onFailure { Log.e(TAG, "Extension listener failed", it) }

                publish(scan())
                val installed = extensionDir(manifest.id)?.let(::describe)
                    ?: throw ExtensionInstallException(ExtensionError.STORAGE_FAILED, "Installed folder missing")
                InstallResult.Installed(installed, previous?.version)
            } catch (e: OTipsException) {
                Log.w(TAG, "Refused package: ${e.message}")
                InstallResult.Failed(ExtensionError.from(e.error))
            } catch (e: ExtensionInstallException) {
                Log.w(TAG, "Install failed: ${e.message}", e.cause)
                InstallResult.Failed(e.error)
            } catch (e: Exception) {
                Log.e(TAG, "Install failed", e)
                InstallResult.Failed(ExtensionError.INSTALL_FAILED)
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    /** Removes the extension, whatever its handler loaded into the app, and everything it saved. */
    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val dir = extensionDir(id) ?: return@withLock
                describe(dir)?.let { extension ->
                    runCatching { handlers[extension.kind]?.remove(extension) }
                        .onFailure { Log.e(TAG, "Handler could not remove $id", it) }
                }
                dir.deleteRecursively()
                publish(scan())
            }
        }
        clearData(id)
    }

    /** Erases what the extension saved; the extension itself stays installed. */
    suspend fun clearData(id: String) {
        if (!ExtensionMetadata.isValidId(id)) return
        withContext(Dispatchers.IO) {
            runCatching { onExtensionEvent(id, null) }.onFailure { Log.e(TAG, "Extension listener failed", it) }
            ExtensionStorage.clear(context, id)
            File(File(root, id), FILES_DIR).deleteRecursively()
        }
        // Anything that bypassed the bridge (IndexedDB, a refused localStorage swap).
        withContext(Dispatchers.Main) {
            runCatching { WebStorage.getInstance().deleteOrigin(origin(id)) }
        }
    }

    /** Writes an `.OTips` copy of an installed extension to [destination]. */
    suspend fun export(id: String, destination: Uri): Boolean = withContext(Dispatchers.IO) {
        val extension = get(id) ?: return@withContext false
        runCatching {
            context.contentResolver.openOutputStream(destination, "w")?.use { out ->
                val bundled = bundledAssetFor(extension)
                val content = contentDir(id)
                when {
                    // The shipped file is byte-for-byte what was installed.
                    bundled != null -> context.assets.open(bundled).use { it.copyTo(out) }
                    content != null -> OTipsWriter.write(
                        manifest = extension.manifest,
                        contentDir = content,
                        output = out,
                        workDir = File(context.cacheDir, WORK_DIR),
                        createdBy = createdBy()
                    )
                    else -> return@runCatching false
                }
                true
            } ?: false
        }.onFailure { Log.w(TAG, "Export of $id failed", it) }.getOrDefault(false)
    }

    /**
     * Packages a plain HTML program (extension guide, section 13) into an
     * `.OTips` file at [destination]. Its metadata block becomes the manifest.
     */
    suspend fun packageHtml(html: Uri, destination: Uri): ConvertResult = withContext(Dispatchers.IO) {
        val bytes = try {
            context.contentResolver.openInputStream(html)?.use { readAtMost(it, HtmlExtensionHandler.MAX_HTML_BYTES + 1) }
        } catch (e: Exception) {
            null
        } ?: return@withContext ConvertResult.Failed(ExtensionError.UNREADABLE)
        if (bytes.size > HtmlExtensionHandler.MAX_HTML_BYTES) {
            return@withContext ConvertResult.Failed(ExtensionError.TOO_LARGE)
        }

        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        if (!HtmlExtensionHandler.looksLikeHtml(text)) return@withContext ConvertResult.Failed(ExtensionError.NOT_HTML)

        val manifest = ExtensionMetadata.manifestFor(text, displayName(html))
        try {
            val written = context.contentResolver.openOutputStream(destination, "w")?.use { out ->
                OTipsWriter.write(
                    manifest = manifest,
                    entries = mapOf(manifest.entry to text.toByteArray(Charsets.UTF_8)),
                    output = out,
                    createdBy = createdBy()
                )
                true
            } ?: false
            if (written) ConvertResult.Converted(manifest) else ConvertResult.Failed(ExtensionError.STORAGE_FAILED)
        } catch (e: Exception) {
            Log.w(TAG, "Could not write the package", e)
            ConvertResult.Failed(ExtensionError.STORAGE_FAILED)
        }
    }

    /** The name an extracted file should be offered under, e.g. "programme-dems.OTips". */
    fun exportFileName(extension: OrthoExtension): String = OTipsFormat.fileName(extension.id)

    // ---- internals -------------------------------------------------------------

    private fun checkInstallable(header: OTipsHeader): ExtensionHandler {
        val handler = handlers[header.manifest.kind]
            ?: throw ExtensionInstallException(ExtensionError.UNSUPPORTED_KIND, "Unknown kind ${header.manifest.kind}")
        if (header.manifest.minAppVersionCode > appVersionCode) {
            throw ExtensionInstallException(
                ExtensionError.REQUIRES_NEWER_APP,
                "Needs app version code ${header.manifest.minAppVersionCode}"
            )
        }
        return handler
    }

    private fun preview(source: ExtensionPackageSource, header: OTipsHeader, installed: OrthoExtension?): PackagePreview {
        val manifest = header.manifest
        val blocker = when {
            !handlers.supports(manifest.kind) -> ExtensionError.UNSUPPORTED_KIND
            manifest.minAppVersionCode > appVersionCode -> ExtensionError.REQUIRES_NEWER_APP
            else -> null
        }
        val status = if (installed == null) {
            PackageStatus.NEW
        } else {
            val order = ExtensionVersion.compare(manifest.version, installed.version)
            when {
                order > 0 -> PackageStatus.UPDATE
                order < 0 -> PackageStatus.OLDER
                installed.payloadSha256 != null && installed.payloadSha256 != header.payloadSha256 ->
                    PackageStatus.CHANGED
                else -> PackageStatus.INSTALLED
            }
        }
        return PackagePreview(source, header, status, installed?.version, blocker)
    }

    private fun loadBundledHeaders(): List<Pair<String, OTipsHeader>> {
        bundledHeaders?.let { return it }
        val names = runCatching { context.assets.list(BundledExtensions.ASSET_DIR) }.getOrNull().orEmpty()
        val headers = names.sorted()
            .filter(OTipsFormat::hasPackageExtension)
            .mapNotNull { name ->
                val path = "${BundledExtensions.ASSET_DIR}/$name"
                runCatching { context.assets.open(path).use { OTipsReader.readHeader(it) } }
                    .onFailure { Log.e(TAG, "Bundled package $path is unreadable", it) }
                    .getOrNull()
                    ?.let { path to it }
            }
        bundledHeaders = headers
        return headers
    }

    /** The shipped package this extension was installed from, when it is still the same content. */
    private fun bundledAssetFor(extension: OrthoExtension): String? {
        if (extension.source != ExtensionSource.BUNDLED) return null
        return loadBundledHeaders().firstOrNull { (_, header) ->
            header.manifest.id == extension.id && header.payloadSha256 == extension.payloadSha256
        }?.first
    }

    /** Swaps [staging] in as [target], carrying over the previous version's private files. */
    private fun commit(staging: File, target: File) {
        val trash = File(root, TRASH_PREFIX + UUID.randomUUID())
        val hadPrevious = target.exists()
        val previousFiles = File(target, FILES_DIR)
        val carriedFiles = File(staging, FILES_DIR)
        if (hadPrevious) {
            if (previousFiles.exists() && !previousFiles.renameTo(carriedFiles)) {
                throw ExtensionInstallException(ExtensionError.STORAGE_FAILED, "Could not keep the extension's files")
            }
            if (!target.renameTo(trash)) {
                carriedFiles.takeIf { it.exists() }?.renameTo(previousFiles)
                throw ExtensionInstallException(ExtensionError.STORAGE_FAILED, "Could not replace the previous version")
            }
        }
        if (!staging.renameTo(target)) {
            if (hadPrevious) {
                trash.renameTo(target)
                carriedFiles.takeIf { it.exists() }?.renameTo(previousFiles)
            }
            throw ExtensionInstallException(ExtensionError.STORAGE_FAILED, "Could not move the new version in place")
        }
        trash.deleteRecursively()
    }

    /**
     * Extensions installed before `.OTips` were a bare `<id>/index.html`. Each
     * becomes `<id>/content/index.html` plus an install record built from its
     * metadata. The id — and so its saved data and WebView origin — is kept.
     */
    private fun migrateLegacyInstalls() {
        val dirs = root.listFiles()?.filter { it.isDirectory && ExtensionMetadata.isValidId(it.name) }.orEmpty()
        for (dir in dirs) {
            val legacyPage = File(dir, LEGACY_HTML)
            if (File(dir, RECORD_FILE).exists() || !legacyPage.isFile) continue
            try {
                val html = legacyPage.readText(Charsets.UTF_8)
                val manifest = ExtensionMetadata.manifestFor(html, null).copy(id = dir.name)
                val content = File(dir, CONTENT_DIR).apply { mkdirs() }
                val moved = File(content, manifest.entry)
                if (!legacyPage.renameTo(moved)) {
                    legacyPage.copyTo(moved, overwrite = true)
                    legacyPage.delete()
                }
                val stamp = moved.lastModified()
                writeRecord(
                    dir,
                    InstallRecord(
                        manifest = manifest,
                        source = ExtensionSource.LEGACY,
                        granted = manifest.permissions,
                        installedAt = stamp,
                        updatedAt = stamp,
                        payloadSha256 = null
                    )
                )
                Log.i(TAG, "Migrated legacy extension ${dir.name}")
            } catch (e: Exception) {
                // Left as it is; the next launch tries again.
                Log.e(TAG, "Could not migrate legacy extension ${dir.name}", e)
            }
        }
    }

    /** Staging and trash folders left by an install the system killed half-way. */
    private fun cleanLeftovers() {
        root.listFiles()
            ?.filter { it.name.startsWith(STAGING_PREFIX) || it.name.startsWith(TRASH_PREFIX) }
            ?.forEach { it.deleteRecursively() }
        File(context.cacheDir, WORK_DIR).deleteRecursively()
    }

    private fun publish(list: List<OrthoExtension>) {
        _extensions.value = list
        runCatching { onExtensionsChanged(list) }.onFailure { Log.e(TAG, "Extension listener failed", it) }
    }

    private fun extensionDir(id: String): File? =
        if (ExtensionMetadata.isValidId(id)) File(root, id).takeIf { it.isDirectory } else null

    private fun scan(): List<OrthoExtension> =
        root.listFiles()
            ?.filter { it.isDirectory && ExtensionMetadata.isValidId(it.name) }
            ?.mapNotNull(::describe)
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    private fun describe(dir: File): OrthoExtension? {
        val record = readRecord(dir) ?: return null
        if (record.manifest.id != dir.name) return null
        val content = File(dir, CONTENT_DIR)
        val size = content.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val extension = record.toExtension(sizeBytes = size, exportable = content.isDirectory)
        return if (extension.exportable) extension else extension.copy(exportable = bundledAssetFor(extension) != null)
    }

    private fun readRecord(dir: File): InstallRecord? {
        val file = File(dir, RECORD_FILE).takeIf { it.isFile } ?: return null
        return try {
            InstallRecord.fromJson(JSONObject(file.readText(Charsets.UTF_8)))
        } catch (e: Exception) {
            Log.e(TAG, "Unreadable install record in ${dir.name}", e)
            null
        }
    }

    private fun writeRecord(dir: File, record: InstallRecord) {
        dir.mkdirs()
        val tmp = File(dir, "$RECORD_FILE.tmp")
        tmp.writeText(record.toJson().toString(2), Charsets.UTF_8)
        val target = File(dir, RECORD_FILE)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not write the install record")
        }
    }

    private fun createdBy() = "Ortho Tips $appVersionName"

    private fun readAtMost(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (total < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit - total))
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment

    /** What `install.json` holds. */
    private data class InstallRecord(
        val manifest: ExtensionManifest,
        val source: ExtensionSource,
        val granted: Set<ExtensionPermission>,
        val installedAt: Long,
        val updatedAt: Long,
        val payloadSha256: String?
    ) {
        fun toExtension(sizeBytes: Long, exportable: Boolean) = OrthoExtension(
            manifest = manifest,
            source = source,
            granted = granted,
            sizeBytes = sizeBytes,
            installedAt = installedAt,
            updatedAt = updatedAt,
            payloadSha256 = payloadSha256,
            exportable = exportable
        )

        fun toJson(): JSONObject = JSONObject()
            .put("record", RECORD_VERSION)
            .put("manifest", manifest.toJson())
            .put("source", source.name)
            .put("granted", JSONArray(ExtensionPermission.keys(granted)))
            .put("installedAt", installedAt)
            .put("updatedAt", updatedAt)
            .put("payloadSha256", payloadSha256 ?: JSONObject.NULL)

        companion object {
            fun fromJson(json: JSONObject): InstallRecord {
                val manifest = ExtensionManifest.fromJson(json.getJSONObject("manifest"))
                val granted = json.optJSONArray("granted")?.let { array ->
                    (0 until array.length()).mapNotNull { ExtensionPermission.fromKey(array.optString(it)) }.toSet()
                } ?: manifest.permissions
                return InstallRecord(
                    manifest = manifest,
                    source = runCatching { ExtensionSource.valueOf(json.optString("source")) }
                        .getOrDefault(ExtensionSource.EXTERNAL),
                    granted = granted,
                    installedAt = json.optLong("installedAt"),
                    updatedAt = json.optLong("updatedAt"),
                    payloadSha256 = json.optString("payloadSha256").takeIf { it.length == 64 }
                )
            }
        }
    }

    companion object {
        private const val TAG = "ExtensionRepository"
        private const val DIR_NAME = "extensions"
        private const val RECORD_FILE = "install.json"
        private const val RECORD_VERSION = 1
        private const val CONTENT_DIR = "content"
        private const val FILES_DIR = "files"
        private const val LEGACY_HTML = "index.html"
        private const val STAGING_PREFIX = ".staging-"
        private const val TRASH_PREFIX = ".trash-"
        private const val WORK_DIR = "otips-work"
        private const val BRIDGE_ASSET = "extension_host/ortho-bridge.js"

        private val HEAD_OPEN = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val HTML_OPEN = Regex("<html\\b[^>]*>", RegexOption.IGNORE_CASE)

        /**
         * Each extension gets its own origin, so whatever the page keeps outside
         * the bridge stays separate from other extensions. `.invalid` never
         * resolves, and network loads are blocked unless `internet` is granted.
         */
        fun origin(id: String): String = "https://$id.ext.orthotips.invalid"

        fun baseUrl(id: String): String = origin(id) + "/"

        internal fun injectBridge(html: String, bridgeJs: String): String {
            val tag = "<script>\n$bridgeJs\n</script>\n"
            val anchor = HEAD_OPEN.find(html) ?: HTML_OPEN.find(html)
                ?: return tag + html
            val at = anchor.range.last + 1
            return html.substring(0, at) + tag + html.substring(at)
        }
    }
}

/** How a package compares with what is installed under the same id. */
enum class PackageStatus {
    /** Not installed. */
    NEW,

    /** The same version and content is already installed. */
    INSTALLED,

    /** Same version number, different content (a rebuilt package). */
    CHANGED,

    /** Newer than the installed version. */
    UPDATE,

    /** Older than the installed version; installing it downgrades. */
    OLDER
}

/**
 * A package described from its header, before anything is unpacked.
 * [blocker] is set when this build of the app cannot install it at all.
 */
data class PackagePreview(
    val source: ExtensionPackageSource,
    val header: OTipsHeader,
    val status: PackageStatus,
    val installedVersion: String?,
    val blocker: ExtensionError?
) {
    val manifest: ExtensionManifest get() = header.manifest
    val installable: Boolean get() = blocker == null
}
