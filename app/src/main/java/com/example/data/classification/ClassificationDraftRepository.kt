package com.example.data.classification

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.example.BuildConfig
import com.example.data.extensions.ClassificationsExtensionHandler
import com.example.data.extensions.ExtensionKinds
import com.example.data.extensions.ExtensionManifest
import com.example.data.extensions.ExtensionMetadata
import com.example.data.extensions.ExtensionPackageSource
import com.example.data.extensions.ExtensionRepository
import com.example.data.extensions.ExtensionVersion
import com.example.data.extensions.OrthoExtension
import com.example.data.extensions.otips.OTipsWriter
import com.example.data.local.model.ChapterRef
import com.example.data.local.model.ClassificationChapter
import com.example.data.local.model.ClassificationIndex
import com.example.data.local.model.DocParagraph
import com.example.data.local.model.ParagraphStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A classification pack being written or edited. */
data class ClassificationDraft(
    val draftId: String,
    /** The id the pack is published under; editing an installed pack keeps its id. */
    val packId: String,
    val name: String,
    val description: String,
    val version: String,
    val icon: String,
    val chapters: List<ChapterRef>,
    val updatedAt: Long
)

/**
 * Drafts of the classification editor. A draft is an unpacked classification
 * pack plus a little metadata, kept in `filesDir/classification_drafts/<draftId>/`:
 *
 *     draft.json            name, description, version, icon, pack id
 *     content/index.json    chapters, as in any classification pack
 *     content/data/<id>.json one chapter each (editor content, schema 2)
 *     content/images/        figures inserted in the documents
 *
 * Nothing here is visible to the rest of the app until [publish]: that packs
 * the draft into an `.OTips` file and installs it like any other extension,
 * through the same verification. The draft stays, so it can be edited and
 * published again (the version goes up each time).
 */
class ClassificationDraftRepository(
    private val context: Context,
    private val extensions: ExtensionRepository
) {
    sealed interface PublishResult {
        data class Published(val extension: OrthoExtension) : PublishResult
        data class Failed(val reason: String) : PublishResult
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = false }
    private val root: File get() = File(context.filesDir, DIR)

    suspend fun list(): List<ClassificationDraft> = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty().filter { File(it, DRAFT_FILE).isFile }.mapNotNull { read(it.name) }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun get(draftId: String): ClassificationDraft? = withContext(Dispatchers.IO) { read(draftId) }

    /** A new, empty pack with one chapter to start typing or pasting into. */
    suspend fun create(name: String): ClassificationDraft = withContext(Dispatchers.IO) {
        val title = name.trim().ifEmpty { "Classifications" }
        val installed = extensions.extensions.value.map { it.id }.toSet()
        val base = "cls-" + (ExtensionMetadata.slug(title).ifEmpty { "pack" }).take(40)
        var packId = base
        var n = 2
        while (packId in installed || drafts().any { it.packId == packId }) packId = "$base-${n++}"

        val draftId = newDraftId()
        writeMeta(draftId, packId, title, "", "1.0", ICON)
        writeIndex(draftId, ClassificationIndex())
        addChapterBlocking(draftId, title)
        read(draftId)!!
    }

    /**
     * Opens an installed pack for editing — the bundled one included. Its
     * chapters are copied into a draft; publishing replaces the installed pack
     * (same id) with a newer version. An existing draft of it is reused.
     */
    suspend fun editInstalled(extension: OrthoExtension): ClassificationDraft = withContext(Dispatchers.IO) {
        drafts().firstOrNull { it.packId == extension.id }?.let { return@withContext it }
        val source = extensions.contentDir(extension.id)
            ?: throw IllegalStateException("${extension.id} has no content")
        val draftId = newDraftId()
        source.copyRecursively(contentDir(draftId), overwrite = true)
        writeMeta(
            draftId,
            extension.id,
            extension.manifest.localizedName(LANGUAGE),
            extension.manifest.localizedDescription(LANGUAGE),
            extension.version,
            extension.icon
        )
        read(draftId)!!
    }

    suspend fun rename(draftId: String, name: String, description: String) = withContext(Dispatchers.IO) {
        val meta = readMeta(draftId) ?: return@withContext
        writeMeta(draftId, meta.optString("packId"), name.trim().ifEmpty { meta.optString("name") }, description.trim(),
            meta.optString("version"), meta.optString("icon", ICON))
    }

    suspend fun delete(draftId: String) = withContext(Dispatchers.IO) {
        draftDir(draftId)?.deleteRecursively()
        Unit
    }

    // ---- chapters ---------------------------------------------------------------

    suspend fun addChapter(draftId: String, title: String): ChapterRef = withContext(Dispatchers.IO) {
        addChapterBlocking(draftId, title)
    }

    suspend fun deleteChapter(draftId: String, chapterFile: String) = withContext(Dispatchers.IO) {
        val index = readIndex(draftId)
        writeIndex(draftId, index.copy(chapters = index.chapters.filterNot { it.file == chapterFile }))
        chapterFile(draftId, chapterFile)?.delete()
        touch(draftId)
    }

    suspend fun moveChapter(draftId: String, chapterFile: String, delta: Int) = withContext(Dispatchers.IO) {
        val chapters = readIndex(draftId).chapters.toMutableList()
        val from = chapters.indexOfFirst { it.file == chapterFile }
        val to = from + delta
        if (from < 0 || to !in chapters.indices) return@withContext
        chapters.add(to, chapters.removeAt(from))
        writeIndex(draftId, ClassificationIndex(chapters))
        touch(draftId)
    }

    /** The chapter as editable lines; chapters written before the editor are converted. */
    suspend fun loadDocument(draftId: String, chapterFile: String): List<DocParagraph> = withContext(Dispatchers.IO) {
        val file = chapterFile(draftId, chapterFile)?.takeIf { it.isFile } ?: return@withContext emptyList()
        ClassificationDocument.fromChapter(json.decodeFromString<ClassificationChapter>(file.readText(Charsets.UTF_8)))
    }

    /** Saves the document and refreshes the chapter's title and counts in the index. */
    suspend fun saveDocument(draftId: String, chapterFile: String, paragraphs: List<DocParagraph>) = withContext(Dispatchers.IO) {
        val file = chapterFile(draftId, chapterFile) ?: return@withContext
        val id = file.nameWithoutExtension
        val chapter = ClassificationDocument.toChapter(id, paragraphs)
        writeAtomically(file, json.encodeToString(ClassificationChapter.serializer(), chapter))
        val index = readIndex(draftId)
        writeIndex(draftId, index.copy(chapters = index.chapters.map { ref ->
            if (ref.file != chapterFile) ref else ref.copy(
                title = chapter.title.ifBlank { ref.title },
                sectionCount = chapter.sections.size,
                classificationCount = chapter.sections.sumOf { it.classifications.size }
            )
        }))
        touch(draftId)
    }

    /** Copies a picked image into the draft; returns its path for an image line. */
    suspend fun importImage(draftId: String, uri: Uri): String? = withContext(Dispatchers.IO) {
        val type = context.contentResolver.getType(uri)
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(type)?.lowercase()
            ?.takeIf { it in IMAGE_EXTENSIONS } ?: "jpg"
        val relative = "images/${UUID.randomUUID().toString().take(12)}.$extension"
        val target = File(contentDir(draftId), relative).apply { parentFile?.mkdirs() }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                ?: return@withContext null
            touch(draftId)
            relative
        }.getOrNull()
    }

    /** The file behind an image line, for showing it while editing. */
    fun imageFile(draftId: String, relative: String): File? =
        if (ExtensionManifest.isSafeRelativePath(relative)) File(contentDir(draftId), relative).takeIf { it.isFile } else null

    // ---- publishing -------------------------------------------------------------

    /** Packs the draft into an `.OTips` package and installs it (a new version each time). */
    suspend fun publish(draftId: String): PublishResult = withContext(Dispatchers.IO) {
        val meta = readMeta(draftId) ?: return@withContext PublishResult.Failed("Draft not found")
        val installed = extensions.get(meta.optString("packId"))
        val version = if (installed == null) meta.optString("version", "1.0")
        else nextVersion(listOf(meta.optString("version"), installed.version).maxWith(Comparator(ExtensionVersion::compare)))
        val manifest = manifestFor(meta, version)
        val file = File(context.cacheDir, "${manifest.id}.OTips")
        try {
            pruneUnusedImages(draftId)
            file.outputStream().buffered().use { out ->
                OTipsWriter.write(manifest, contentDir(draftId), out, File(context.cacheDir, WORK_DIR), createdBy())
            }
            when (val result = extensions.install(ExtensionPackageSource.LocalFile(file))) {
                is ExtensionRepository.InstallResult.Installed -> {
                    writeMeta(draftId, manifest.id, manifest.name, manifest.description, version, manifest.icon)
                    PublishResult.Published(result.extension)
                }
                is ExtensionRepository.InstallResult.Failed -> PublishResult.Failed(result.error.name)
            }
        } finally {
            file.delete()
        }
    }

    /** Writes the draft as an `.OTips` file to share, without installing it. */
    suspend fun export(draftId: String, destination: Uri): Boolean = withContext(Dispatchers.IO) {
        val meta = readMeta(draftId) ?: return@withContext false
        runCatching {
            pruneUnusedImages(draftId)
            context.contentResolver.openOutputStream(destination, "w")?.use { out ->
                OTipsWriter.write(manifestFor(meta, meta.optString("version", "1.0")), contentDir(draftId), out,
                    File(context.cacheDir, WORK_DIR), createdBy())
                true
            } ?: false
        }.getOrDefault(false)
    }

    fun exportFileName(draft: ClassificationDraft): String = "${draft.packId}.OTips"

    // ---- internals --------------------------------------------------------------

    private fun manifestFor(meta: JSONObject, version: String) = ExtensionManifest(
        id = meta.optString("packId"),
        kind = ExtensionKinds.CLASSIFICATIONS,
        name = meta.optString("name").ifBlank { "Classifications" },
        description = meta.optString("description"),
        version = version,
        icon = meta.optString("icon", ICON),
        author = "",
        minAppVersionCode = BuildConfig.VERSION_CODE,
        contentSchema = ClassificationsExtensionHandler.SUPPORTED_SCHEMA
    )

    /** Figures removed from every document are not shipped. */
    private fun pruneUnusedImages(draftId: String) {
        val content = contentDir(draftId)
        val used = readIndex(draftId).chapters.flatMap { ref ->
            chapterFile(draftId, ref.file)?.takeIf { it.isFile }?.let {
                ClassificationDocument.images(json.decodeFromString<ClassificationChapter>(it.readText(Charsets.UTF_8)))
            }.orEmpty()
        }.toSet()
        File(content, "images").listFiles()?.forEach { file ->
            if ("images/${file.name}" !in used) file.delete()
        }
    }

    private fun addChapterBlocking(draftId: String, title: String): ChapterRef {
        val index = readIndex(draftId)
        val base = ClassificationDocument.slug(title).ifEmpty { "chapter" }
        var id = base
        var n = 2
        while (index.chapters.any { it.id == id }) id = "${base}_${n++}"
        val ref = ChapterRef(id = id, title = title.trim(), file = "data/$id.json")
        val file = File(contentDir(draftId), ref.file).apply { parentFile?.mkdirs() }
        val document = listOf(DocParagraph(ParagraphStyles.TITLE, ref.title))
        writeAtomically(file, json.encodeToString(ClassificationChapter.serializer(), ClassificationDocument.toChapter(id, document)))
        writeIndex(draftId, index.copy(chapters = index.chapters + ref))
        touch(draftId)
        return ref
    }

    private fun drafts(): List<ClassificationDraft> =
        root.listFiles().orEmpty().mapNotNull { read(it.name) }

    private fun read(draftId: String): ClassificationDraft? {
        val meta = readMeta(draftId) ?: return null
        return ClassificationDraft(
            draftId = draftId,
            packId = meta.optString("packId"),
            name = meta.optString("name"),
            description = meta.optString("description"),
            version = meta.optString("version", "1.0"),
            icon = meta.optString("icon", ICON),
            chapters = readIndex(draftId).chapters,
            updatedAt = meta.optLong("updatedAt")
        )
    }

    private fun readMeta(draftId: String): JSONObject? =
        draftDir(draftId)?.let { File(it, DRAFT_FILE) }?.takeIf { it.isFile }
            ?.let { runCatching { JSONObject(it.readText(Charsets.UTF_8)) }.getOrNull() }

    private fun writeMeta(draftId: String, packId: String, name: String, description: String, version: String, icon: String) {
        val dir = File(root, draftId).apply { mkdirs() }
        writeAtomically(
            File(dir, DRAFT_FILE),
            JSONObject()
                .put("packId", packId)
                .put("name", name)
                .put("description", description)
                .put("version", version.ifBlank { "1.0" })
                .put("icon", icon.ifBlank { ICON })
                .put("updatedAt", System.currentTimeMillis())
                .toString(2)
        )
    }

    private fun touch(draftId: String) {
        val meta = readMeta(draftId) ?: return
        writeAtomically(File(File(root, draftId), DRAFT_FILE), meta.put("updatedAt", System.currentTimeMillis()).toString(2))
    }

    private fun readIndex(draftId: String): ClassificationIndex =
        File(contentDir(draftId), INDEX).takeIf { it.isFile }
            ?.let { runCatching { json.decodeFromString<ClassificationIndex>(it.readText(Charsets.UTF_8)) }.getOrNull() }
            ?: ClassificationIndex()

    private fun writeIndex(draftId: String, index: ClassificationIndex) {
        writeAtomically(File(contentDir(draftId), INDEX).apply { parentFile?.mkdirs() },
            json.encodeToString(ClassificationIndex.serializer(), index))
    }

    private fun draftDir(draftId: String): File? =
        if (ExtensionMetadata.isValidId(draftId)) File(root, draftId).takeIf { it.isDirectory } else null

    private fun contentDir(draftId: String): File = File(File(root, draftId), CONTENT)

    private fun chapterFile(draftId: String, relative: String): File? =
        if (ExtensionManifest.isSafeRelativePath(relative)) File(contentDir(draftId), relative) else null

    private fun newDraftId(): String = "d" + UUID.randomUUID().toString().replace("-", "").take(16)

    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun createdBy() = "Ortho Tips ${BuildConfig.VERSION_NAME} classification editor"

    companion object {
        private const val DIR = "classification_drafts"
        private const val DRAFT_FILE = "draft.json"
        private const val CONTENT = "content"
        private const val INDEX = "index.json"
        private const val WORK_DIR = "otips-work"
        private const val ICON = "📋"
        private const val LANGUAGE = "fr"
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif")

        /** "1.0" → "1.1", "2.9" → "2.10", "3" → "3.1". */
        fun nextVersion(version: String): String {
            val parts = version.split('.').mapNotNull { it.toIntOrNull() }
            return when {
                parts.isEmpty() -> "1.1"
                parts.size == 1 -> "${parts[0]}.1"
                else -> (parts.dropLast(1) + (parts.last() + 1)).joinToString(".")
            }
        }
    }
}
