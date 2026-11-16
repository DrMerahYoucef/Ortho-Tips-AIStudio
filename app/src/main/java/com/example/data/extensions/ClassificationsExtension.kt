package com.example.data.extensions

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Classification packs: an `index.json` listing chapters, one JSON file per
 * chapter (sections → classifications → content blocks) and the figures they
 * reference. The content stays on disk and [com.example.data.repository.ClassificationRepository]
 * reads it from there; several packs can be installed at once and are shown
 * together.
 *
 * Validation walks the whole pack before it is accepted, so a missing chapter
 * file or figure is reported at install time rather than as a blank screen.
 */
class ClassificationsExtensionHandler : ExtensionHandler {

    override val kind: String = ExtensionKinds.CLASSIFICATIONS

    override val maxPayloadBytes: Long = 128L * 1024 * 1024

    override fun validate(manifest: ExtensionManifest, content: File) {
        if (manifest.contentSchema > SUPPORTED_SCHEMA) {
            throw ExtensionInstallException(ExtensionError.UNSUPPORTED_FORMAT, "Classification schema ${manifest.contentSchema}")
        }
        val index = readJson(File(content, INDEX)) ?: invalid("missing or unreadable $INDEX")
        val chapters = index.optJSONArray("chapters") ?: invalid("$INDEX has no chapters")
        for (i in 0 until chapters.length()) {
            val ref = chapters.optJSONObject(i) ?: invalid("chapter $i is not an object")
            val path = ref.optString("file")
            if (ref.optString("id").isBlank() || !ExtensionManifest.isSafeRelativePath(path)) {
                invalid("chapter $i has an invalid id or file")
            }
            val chapter = readJson(File(content, path)) ?: invalid("chapter file $path is missing or unreadable")
            imagesOf(chapter).forEach { image ->
                if (!ExtensionManifest.isSafeRelativePath(image) || !File(content, image).isFile) {
                    invalid("figure $image referenced by $path is missing")
                }
            }
        }
    }

    private fun imagesOf(chapter: JSONObject): Sequence<String> = sequence {
        val sections = chapter.optJSONArray("sections") ?: JSONArray()
        for (s in 0 until sections.length()) {
            val entries = sections.optJSONObject(s)?.optJSONArray("classifications") ?: continue
            for (e in 0 until entries.length()) {
                val images = entries.optJSONObject(e)?.optJSONArray("images") ?: JSONArray()
                for (k in 0 until images.length()) yield(images.optString(k))
                // Figures placed inside editor content.
                val content = entries.optJSONObject(e)?.optJSONArray("content") ?: continue
                for (k in 0 until content.length()) {
                    val paragraph = content.optJSONObject(k) ?: continue
                    if (paragraph.optString("style") == "image") yield(paragraph.optString("image"))
                }
            }
        }
    }

    private fun readJson(file: File): JSONObject? =
        if (file.isFile) runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() else null

    private fun invalid(reason: String): Nothing =
        throw ExtensionInstallException(ExtensionError.INVALID_CONTENT, "Invalid classification pack: $reason")

    companion object {
        /** The id of the bundled classification pack. */
        const val ID = "classifications"
        const val INDEX = "index.json"
        /** 1: blocks and images; 2: adds editor content (Word-style paragraphs). */
        const val SUPPORTED_SCHEMA = 2
    }
}
