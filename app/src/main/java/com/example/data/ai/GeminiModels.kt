package com.example.data.ai

/** The three Gemini families the app offers ("le plus récent" of each). */
enum class ModelFamily(val key: String) { FLASH_LITE("flash-lite"), FLASH("flash"), PRO("pro") }

/** A text model usable for generateContent, as listed by Google. */
data class GeminiModel(
    val id: String,
    val displayName: String,
    val family: ModelFamily,
    val version: Double,
    val preview: Boolean
)

/**
 * Which Gemini model to call. Pure Kotlin, unit-tested.
 *
 * A preference is either `auto:<family>` (the newest model of that family,
 * stable before preview) or an exact model id. The candidates are tried in
 * order: if one fails (removed, overloaded, quota), the next one is used.
 */
object GeminiModels {

    const val AUTO_PREFIX = "auto:"
    const val DEFAULT_PREFERENCE = "auto:flash"
    private const val MAX_CANDIDATES = 6

    private val PATTERN = Regex("^gemini-(\\d+(?:\\.\\d+)?)-(flash-lite|flash|pro)(.*)$")
    /** Specialised variants that are not general text models. */
    private val EXCLUDED = Regex("tts|image|audio|live|embedding|computer|robotics|exp|thinking|customtools")

    /** A listed model, or null when it is not a general Gemini text model. */
    fun parse(name: String, displayName: String = name): GeminiModel? {
        val id = name.removePrefix("models/")
        val m = PATTERN.matchEntire(id) ?: return null
        val rest = m.groupValues[3]
        if (EXCLUDED.containsMatchIn(rest)) return null
        val family = ModelFamily.entries.first { it.key == m.groupValues[2] }
        return GeminiModel(id, displayName.ifBlank { id }, family, m.groupValues[1].toDouble(), rest.contains("preview"))
    }

    fun familyOf(preference: String): ModelFamily? =
        if (preference.startsWith(AUTO_PREFIX)) ModelFamily.entries.firstOrNull { it.key == preference.removePrefix(AUTO_PREFIX) } else null

    /** Newest first; at the same version stable before preview, then the plain id before "-001" builds. */
    fun sorted(models: List<GeminiModel>): List<GeminiModel> =
        models.sortedWith(compareByDescending<GeminiModel> { it.version }.thenBy { it.preview }.thenBy { it.id.length }.thenBy { it.id })

    /** The models to try, in order, for [preference]. */
    fun candidates(models: List<GeminiModel>, preference: String): List<String> {
        val out = LinkedHashSet<String>()
        val family = familyOf(preference)
        if (family == null && preference.isNotBlank()) out += preference.removePrefix("models/")
        val first = family ?: models.firstOrNull { it.id == preference }?.family ?: ModelFamily.FLASH
        val order = listOf(first) + when (first) {
            ModelFamily.FLASH_LITE -> listOf(ModelFamily.FLASH, ModelFamily.PRO)
            ModelFamily.FLASH -> listOf(ModelFamily.FLASH_LITE, ModelFamily.PRO)
            ModelFamily.PRO -> listOf(ModelFamily.FLASH, ModelFamily.FLASH_LITE)
        }
        val byFamily = sorted(models).groupBy { it.family }
        // Stable models first, family by family; previews only after them.
        for (f in order) byFamily[f].orEmpty().filter { !it.preview }.forEach { out += it.id }
        for (f in order) byFamily[f].orEmpty().filter { it.preview }.forEach { out += it.id }
        // Google's own aliases, in case the list could not be read.
        order.forEach { out += "gemini-${it.key}-latest" }
        return out.take(MAX_CANDIDATES + 3).toList()
    }
}
