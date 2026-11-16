package com.example.data.extensions

import java.text.Normalizer

/** Where an installed extension came from. */
enum class ExtensionSource {
    /** Shipped inside the app's assets and installed from there. */
    BUNDLED,

    /** Imported from an `.OTips` file the user picked. */
    EXTERNAL,

    /** Installed as a bare `.html` file before `.OTips` existed; migrated in place. */
    LEGACY
}

/**
 * One installed extension.
 *
 * [manifest] is what the package declared; [granted] is what it may actually
 * use. They differ only for extensions that predate the permission system,
 * which keep the export capability they always had (see
 * [ExtensionMetadata.Parsed.effectivePermissions]).
 */
data class OrthoExtension(
    val manifest: ExtensionManifest,
    val source: ExtensionSource,
    val granted: Set<ExtensionPermission>,
    val sizeBytes: Long,
    val installedAt: Long,
    val updatedAt: Long,
    /** Hash of the installed package payload; null for migrated legacy installs. */
    val payloadSha256: String?,
    /** Whether an `.OTips` copy can be produced (content on disk, or shipped in the app). */
    val exportable: Boolean
) {
    val id: String get() = manifest.id
    val kind: String get() = manifest.kind
    val name: String get() = manifest.name
    val description: String get() = manifest.description
    val version: String get() = manifest.version
    val icon: String get() = manifest.icon
    val author: String get() = manifest.author

    val allowNetwork: Boolean get() = ExtensionPermission.INTERNET in granted

    fun has(permission: ExtensionPermission): Boolean = permission in granted
}

/**
 * Reads the `<meta name="ortho-ext-…" content="…">` block from an HTML
 * program's `<head>` (extension guide, section 3). Used when an HTML program is
 * packaged into an `.OTips` file and when migrating pre-`.OTips` installs.
 * Every field is optional: a plain HTML program with only a `<title>` still
 * packages, it just gets a default icon and an id made from its title.
 */
object ExtensionMetadata {

    data class Parsed(
        val id: String?,
        val name: String?,
        val description: String?,
        val version: String?,
        val icon: String?,
        val author: String?,
        /** Parsed `ortho-ext-permissions`; null when the tag is absent. */
        val declaredPermissions: Set<ExtensionPermission>?,
        /** Legacy `ortho-ext-network="allow"`, a synonym for `internet`. */
        val legacyNetwork: Boolean
    ) {
        /**
         * What the program may use once installed.
         *
         * With an `ortho-ext-permissions` tag: exactly what it lists (plus
         * `internet` for the legacy network tag). Without one, the program was
         * written before permissions existed, when `saveFile` and the file
         * picker were available to everyone; it keeps `import_export` so its
         * backup buttons — often the user's only copy of its data — still work.
         */
        val effectivePermissions: Set<ExtensionPermission>
            get() {
                val base = declaredPermissions ?: setOf(ExtensionPermission.IMPORT_EXPORT)
                return if (legacyNetwork) base + ExtensionPermission.INTERNET else base
            }
    }

    const val MAX_ID_LENGTH = 48
    private const val PREFIX = "ortho-ext-"

    private val META = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val TITLE = Regex(
        "<title[^>]*>(.*?)</title>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val HEAD_END = Regex("</head\\s*>", RegexOption.IGNORE_CASE)
    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val WHITESPACE = Regex("\\s+")
    private val VALID_ID = Regex("[a-z0-9](?:[a-z0-9-]{0,${MAX_ID_LENGTH - 2}}[a-z0-9])?")

    fun parse(html: String): Parsed {
        val head = HEAD_END.find(html)?.let { html.substring(0, it.range.first) } ?: html
        val values = HashMap<String, String>()
        for (tag in META.findAll(head)) {
            val attrs = HashMap<String, String>()
            for (m in ATTR.findAll(tag.value)) {
                attrs[m.groupValues[1].lowercase()] = m.groups[2]?.value ?: m.groups[3]?.value ?: ""
            }
            val name = attrs["name"]?.trim()?.lowercase() ?: continue
            if (!name.startsWith(PREFIX)) continue
            values[name.removePrefix(PREFIX)] = decodeEntities(attrs["content"].orEmpty()).trim()
        }
        val title = TITLE.find(head)?.groupValues?.get(1)
            ?.let { decodeEntities(it).replace(WHITESPACE, " ").trim() }

        return Parsed(
            id = values["id"]?.let(::slug)?.takeIf { it.isNotEmpty() },
            name = values["name"]?.takeIf { it.isNotEmpty() } ?: title?.takeIf { it.isNotEmpty() },
            description = values["description"]?.takeIf { it.isNotEmpty() },
            version = values["version"]?.takeIf { it.isNotEmpty() },
            icon = values["icon"]?.takeIf { it.isNotEmpty() },
            author = values["author"]?.takeIf { it.isNotEmpty() },
            declaredPermissions = values["permissions"]?.let(ExtensionPermission::parseList),
            legacyNetwork = values["network"].equals("allow", ignoreCase = true)
        )
    }

    /**
     * The manifest an HTML program gets when it is packaged into an `.OTips`
     * file. The id falls back to the name, then to [fallbackName] (usually the
     * file name), so a program without the metadata block still packages.
     */
    fun manifestFor(html: String, fallbackName: String?): ExtensionManifest {
        val meta = parse(html)
        val fileStem = fallbackName?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() }
        val id = meta.id?.takeIf(::isValidId)
            ?: meta.name?.let(::slug)?.takeIf(::isValidId)
            ?: fileStem?.let(::slug)?.takeIf(::isValidId)
            ?: ("extension-" + Integer.toHexString(html.hashCode()))
        return ExtensionManifest(
            id = id,
            kind = ExtensionKinds.HTML,
            name = meta.name ?: fileStem ?: id,
            description = meta.description.orEmpty(),
            version = meta.version ?: ExtensionManifest.DEFAULT_VERSION,
            icon = meta.icon ?: ExtensionManifest.DEFAULT_ICON,
            author = meta.author.orEmpty(),
            permissions = meta.effectivePermissions,
            entry = ExtensionManifest.DEFAULT_ENTRY
        )
    }

    /** "Programme DEMS — v2" → "programme-dems-v2". */
    fun slug(text: String): String {
        val ascii = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return ascii.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(MAX_ID_LENGTH)
            .trim('-')
    }

    fun isValidId(id: String): Boolean = VALID_ID.matches(id)

    fun decodeEntities(text: String): String = ENTITY.replace(text) { m ->
        val entity = m.groupValues[1]
        when {
            entity.startsWith("#x") || entity.startsWith("#X") ->
                entity.substring(2).toIntOrNull(16)?.let(::codePoint)
            entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let(::codePoint)
            else -> NAMED[entity]
        } ?: m.value
    }

    private fun codePoint(cp: Int): String? =
        if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else null

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to "\u00A0", "rsquo" to "\u2019", "lsquo" to "\u2018",
        "ldquo" to "\u201C", "rdquo" to "\u201D", "laquo" to "\u00AB", "raquo" to "\u00BB",
        "ndash" to "\u2013", "mdash" to "\u2014", "middot" to "\u00B7", "hellip" to "\u2026",
        "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "agrave" to "à",
        "acirc" to "â", "ccedil" to "ç", "ocirc" to "ô", "ucirc" to "û", "icirc" to "î"
    )
}
