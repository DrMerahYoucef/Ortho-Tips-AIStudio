package com.example.data.extensions

import com.example.data.extensions.otips.OTipsError
import com.example.data.extensions.otips.OTipsException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Identity and requirements of one `.OTips` package.
 *
 * It travels in the package header (so the import screen can describe a package
 * without unpacking it) and is kept next to the installed content. Only plain
 * Java/`org.json` is used here so the same class can build packages at compile
 * time, outside Android.
 *
 * @property id permanent identity (extension guide, section 3). Installing a
 *   package whose id is already installed replaces it and keeps its data.
 * @property kind see [ExtensionKinds]; decides which handler installs it.
 * @property version free text such as `2.1`; ordered by [ExtensionVersion].
 * @property entry for [ExtensionKinds.HTML], the page opened inside the content.
 * @property minAppVersionCode lowest app `versionCode` able to install it.
 * @property contentSchema version of the kind-specific content layout, so a
 *   handler can migrate or refuse an older/newer shape explicitly.
 * @property attributes small facts shown in the UI without widening this class
 *   for every kind. `name.<lang>` and `description.<lang>` (e.g. `name.fr`)
 *   translate the name and description; see [localizedName].
 */
data class ExtensionManifest(
    val id: String,
    val kind: String,
    val name: String,
    val description: String = "",
    val version: String = DEFAULT_VERSION,
    val icon: String = DEFAULT_ICON,
    val author: String = "",
    val permissions: Set<ExtensionPermission> = emptySet(),
    val entry: String = DEFAULT_ENTRY,
    val minAppVersionCode: Int = 0,
    val contentSchema: Int = 1,
    val attributes: Map<String, String> = emptyMap()
) {

    /** The name in [language] (`"fr"`, `"en"`), falling back to [name]. */
    fun localizedName(language: String): String = attributes["name.$language"]?.takeIf { it.isNotBlank() } ?: name

    fun localizedDescription(language: String): String =
        attributes["description.$language"]?.takeIf { it.isNotBlank() } ?: description

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind)
        .put("name", name)
        .put("description", description)
        .put("version", version)
        .put("icon", icon)
        .put("author", author)
        .put("permissions", JSONArray(ExtensionPermission.keys(permissions)))
        .put("entry", entry)
        .put("minAppVersionCode", minAppVersionCode)
        .put("contentSchema", contentSchema)
        .put("attributes", JSONObject(attributes.toSortedMap()))

    companion object {
        const val DEFAULT_ICON = "🧩"
        const val DEFAULT_VERSION = "1.0"
        const val DEFAULT_ENTRY = "index.html"

        private const val MAX_NAME = 80
        private const val MAX_DESCRIPTION = 500
        private const val MAX_TEXT = 120
        private const val MAX_ATTRIBUTES = 32

        /**
         * Reads and validates a manifest. Anything that would later be used as a
         * path or an identity is checked here, once, so no caller has to trust
         * a package's own claims.
         */
        fun fromJson(json: JSONObject): ExtensionManifest {
            val id = json.optString("id").trim()
            if (!ExtensionMetadata.isValidId(id)) invalid("invalid id '$id'")

            val kind = json.optString("kind").trim()
            if (!ExtensionKinds.isValid(kind)) invalid("invalid kind '$kind'")

            val name = json.optString("name").trim()
            if (name.isEmpty()) invalid("missing name")

            val entry = json.optString("entry", DEFAULT_ENTRY).trim().ifEmpty { DEFAULT_ENTRY }
            if (!isSafeRelativePath(entry)) invalid("unsafe entry '$entry'")

            val permissions = json.optJSONArray("permissions")?.let { array ->
                (0 until array.length()).mapNotNull { ExtensionPermission.fromKey(array.optString(it)) }.toSet()
            }.orEmpty()

            val attributes = json.optJSONObject("attributes")?.let { obj ->
                obj.keys().asSequence().take(MAX_ATTRIBUTES)
                    .associateWith { key -> obj.optString(key).take(MAX_DESCRIPTION) }
            }.orEmpty()

            return ExtensionManifest(
                id = id,
                kind = kind,
                name = name.take(MAX_NAME),
                description = json.optString("description").trim().take(MAX_DESCRIPTION),
                version = json.optString("version").trim().ifEmpty { DEFAULT_VERSION }.take(MAX_TEXT),
                icon = json.optString("icon").trim().ifEmpty { DEFAULT_ICON }.take(16),
                author = json.optString("author").trim().take(MAX_TEXT),
                permissions = permissions,
                entry = entry,
                minAppVersionCode = json.optInt("minAppVersionCode", 0).coerceAtLeast(0),
                contentSchema = json.optInt("contentSchema", 1).coerceAtLeast(1),
                attributes = attributes
            )
        }

        /**
         * A path inside a package: forward slashes, no leading slash, no `.` or
         * `..` segments, no backslashes or control characters. Used for the
         * manifest entry and every archive entry, which closes "zip slip".
         */
        fun isSafeRelativePath(path: String): Boolean {
            if (path.isEmpty() || path.length > 512) return false
            if (path.startsWith("/") || path.contains('\\') || path.contains(':')) return false
            if (path.any { it.code < 0x20 }) return false
            return path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
        }

        private fun invalid(reason: String): Nothing =
            throw OTipsException(OTipsError.INVALID_MANIFEST, "Invalid manifest: $reason")
    }
}

/**
 * Orders the free-text versions of the guide (`1.0`, `2.1`, `2.10`, `3.0-beta`).
 * Numeric runs compare as numbers and missing trailing parts count as zero, so
 * `2.10` is newer than `2.9` and `1.0` equals `1`.
 */
object ExtensionVersion {
    private val PARTS = Regex("\\d+|[A-Za-z]+")

    fun compare(a: String, b: String): Int {
        val left = PARTS.findAll(a).map { it.value }.toList()
        val right = PARTS.findAll(b).map { it.value }.toList()
        for (i in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrNull(i)
            val y = right.getOrNull(i)
            val result = when {
                x == null -> if (y!!.isNumeric() && y.trimStart('0').isEmpty()) 0 else if (y.isNumeric()) -1 else 1
                y == null -> if (x.isNumeric() && x.trimStart('0').isEmpty()) 0 else if (x.isNumeric()) 1 else -1
                x.isNumeric() && y.isNumeric() -> x.toBigInteger().compareTo(y.toBigInteger())
                x.isNumeric() -> 1 // "1.0" is newer than "1.beta"
                y.isNumeric() -> -1
                else -> x.compareTo(y, ignoreCase = true)
            }
            if (result != 0) return result.coerceIn(-1, 1)
        }
        return 0
    }

    private fun String.isNumeric() = isNotEmpty() && all { it in '0'..'9' }
}
