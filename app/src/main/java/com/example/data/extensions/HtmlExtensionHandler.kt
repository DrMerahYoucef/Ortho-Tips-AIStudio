package com.example.data.extensions

import java.io.File

/**
 * Web programs (extension guide, sections 1–13): an HTML page, plus any files
 * the page reads through `OrthoTips.assets` when it holds the `assets`
 * permission. The content stays on disk and the runner serves it from there.
 */
class HtmlExtensionHandler : ExtensionHandler {

    override val kind: String = ExtensionKinds.HTML

    override val maxPayloadBytes: Long = MAX_PACKAGE_BYTES

    override fun validate(manifest: ExtensionManifest, content: File) {
        val page = File(content, manifest.entry)
        if (!page.isFile) {
            throw ExtensionInstallException(ExtensionError.INVALID_CONTENT, "Missing entry page ${manifest.entry}")
        }
        if (page.length() > MAX_HTML_BYTES) {
            throw ExtensionInstallException(ExtensionError.TOO_LARGE, "Entry page exceeds 15 MB")
        }
        val prefix = page.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(SNIFF_CHARS)
            val n = reader.read(buffer)
            if (n > 0) String(buffer, 0, n) else ""
        }
        if (!looksLikeHtml(prefix)) {
            throw ExtensionInstallException(ExtensionError.NOT_HTML, "Entry page is not HTML")
        }
    }

    companion object {
        /** The guide's limit for the page itself (section 2, rule 1). */
        const val MAX_HTML_BYTES = 15 * 1024 * 1024

        /** Page plus bundled assets, compressed. */
        const val MAX_PACKAGE_BYTES = 64L * 1024 * 1024

        private const val SNIFF_CHARS = 200_000

        private val LOOKS_LIKE_HTML = Regex(
            "<\\s*(!doctype\\s+html|html|head|body|script|style|div|main|section|svg)\\b",
            RegexOption.IGNORE_CASE
        )

        fun looksLikeHtml(text: String): Boolean =
            LOOKS_LIKE_HTML.containsMatchIn(text.removePrefix("\uFEFF").take(SNIFF_CHARS))
    }
}
