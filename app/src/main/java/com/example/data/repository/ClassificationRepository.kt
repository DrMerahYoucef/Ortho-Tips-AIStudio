package com.example.data.repository

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.data.extensions.ExtensionKinds
import com.example.data.extensions.ExtensionManifest
import com.example.data.extensions.ExtensionRepository
import com.example.data.extensions.OrthoExtension
import com.example.data.local.model.ClassificationChapter
import com.example.data.local.model.ClassificationIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException

/**
 * Classification reference, read from the installed classification extensions
 * (see [com.example.data.extensions.ClassificationsExtensionHandler]).
 *
 * Every installed pack contributes its chapters, in install order. Chapter
 * files and figures are addressed as `<packId>/<path inside the pack>`, so
 * packs never collide and callers keep treating those strings as opaque keys.
 * With no pack installed the index is simply empty.
 *
 * Parsed chapters are cached until the set of installed packs changes.
 */
class ClassificationRepository(private val extensions: ExtensionRepository) {

    private val json = Json { ignoreUnknownKeys = true }

    private class Cache(
        val revision: String,
        val index: ClassificationIndex,
        val chapters: MutableMap<String, ClassificationChapter> = mutableMapOf()
    )

    @Volatile private var cache: Cache? = null

    /** Changes whenever a classification pack is installed, updated or removed. */
    val revision: Flow<String> = extensions.extensions
        .map(::revisionOf)
        .distinctUntilChanged()

    suspend fun getIndex(): ClassificationIndex = withContext(Dispatchers.IO) { current().index }

    /** [fileKey] is a [com.example.data.local.model.ChapterRef.file] from [getIndex]. */
    suspend fun getChapter(fileKey: String): ClassificationChapter = withContext(Dispatchers.IO) {
        val snapshot = current()
        snapshot.chapters[fileKey] ?: run {
            val (packId, path) = split(fileKey) ?: throw FileNotFoundException(fileKey)
            val file = resolve(packId, path) ?: throw FileNotFoundException(fileKey)
            val chapter = json.decodeFromString<ClassificationChapter>(file.readText(Charsets.UTF_8))
            // Figures become pack-qualified keys, like chapter files.
            val qualified = chapter.copy(
                sections = chapter.sections.map { section ->
                    section.copy(
                        classifications = section.classifications.map { entry ->
                            entry.copy(
                                images = entry.images.map { "$packId/$it" },
                                content = entry.content.map { p -> p.image?.let { p.copy(image = "$packId/$it") } ?: p }
                            )
                        }
                    )
                }
            )
            qualified.also { snapshot.chapters[fileKey] = it }
        }
    }

    /** [imageKey] is an entry of [com.example.data.local.model.ClassificationEntry.images]. */
    fun loadImage(imageKey: String): ImageBitmap? = try {
        split(imageKey)
            ?.let { (packId, path) -> resolve(packId, path) }
            ?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
    } catch (e: Exception) {
        null
    }

    // ---- internals -------------------------------------------------------------

    private suspend fun current(): Cache {
        extensions.ensureScanned()
        val packs = packs(extensions.extensions.value)
        val revision = revisionOf(extensions.extensions.value)
        cache?.takeIf { it.revision == revision }?.let { return it }

        val seenIds = mutableSetOf<String>()
        val chapters = packs.flatMap { pack ->
            val file = extensions.contentDir(pack.id)?.let { File(it, INDEX) }?.takeIf { it.isFile }
                ?: return@flatMap emptyList()
            val index = runCatching { json.decodeFromString<ClassificationIndex>(file.readText(Charsets.UTF_8)) }
                .getOrNull() ?: return@flatMap emptyList()
            index.chapters.map { ref ->
                // Chapter ids stay as authored unless two packs use the same one.
                val id = if (seenIds.add(ref.id)) ref.id else "${pack.id}/${ref.id}"
                ref.copy(id = id, file = "${pack.id}/${ref.file}")
            }
        }
        return Cache(revision, ClassificationIndex(chapters)).also { cache = it }
    }

    private fun packs(all: List<OrthoExtension>): List<OrthoExtension> =
        all.filter { it.kind == ExtensionKinds.CLASSIFICATIONS }.sortedBy { it.installedAt }

    private fun revisionOf(all: List<OrthoExtension>): String =
        packs(all).joinToString("|") { "${it.id}@${it.payloadSha256 ?: it.updatedAt}" }

    private fun split(key: String): Pair<String, String>? {
        val slash = key.indexOf('/')
        if (slash <= 0) return null
        return key.substring(0, slash) to key.substring(slash + 1)
    }

    private fun resolve(packId: String, path: String): File? {
        if (!ExtensionManifest.isSafeRelativePath(path)) return null
        return extensions.contentDir(packId)?.let { File(it, path) }?.takeIf { it.isFile }
    }

    private companion object {
        const val INDEX = "index.json"
    }
}
