package com.example.data.importer

import android.content.Context
import android.net.Uri
import com.example.data.local.OrthoDatabase
import com.example.data.local.entity.BoneEntity
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.ImageEntity
import com.example.data.local.entity.SectionEntity
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Result of a successful [BoneDatabaseImporter.importZip] call.
 */
data class ImportSummary(
    val chapterCount: Int,
    val boneCount: Int,
    val figureCount: Int
)

/**
 * Imports the clinical/bone-fracture reference database from a .zip archive.
 *
 * Expected archive layout (produced by the reader's PDF-extraction export):
 * ```
 * archive.zip
 * ├── bones.json
 * ├── clavicle/
 * │   ├── p255-fig1.jpg
 * │   └── ...
 * ├── humerus-proximal/
 * │   └── ...
 * └── ...
 * ```
 *
 * `bones.json` shape:
 * ```
 * {
 *   "generated": "...",
 *   "source": "...",
 *   "bones": {
 *     "<boneId>": {
 *       "id": "clavicle",
 *       "name": "Clavicle",
 *       "region": "Shoulder",
 *       "section": "Shoulder / Arm",
 *       "notes": "",
 *       "pageStart": 253,
 *       "pageEnd": 270,
 *       "figureCount": 19,
 *       "figures": [
 *         { "file": "p255-fig1.jpg", "page": 255, "index": 0, "label": "5-1", "width": 873, "height": 508, "mime": "image/jpeg" }
 *       ],
 *       "sections": [
 *         { "type": "ANATOMY", "heading": "Pathoanatomy", "text": "..." },
 *         { "type": "APPROACH", "heading": "Surgical Approaches", "text": "..." },
 *         { "type": "REDUCTION", "heading": "Reduction and Implant Techniques", "text": "..." },
 *         { "type": "FIXATION", "heading": "Implants", "text": "..." },
 *         { "type": "PEARLS", "heading": "Tips", "text": "..." },
 *         { "type": "COMPLICATIONS", "heading": "Complications", "text": "..." },
 *         { "type": "REHAB", "heading": "Postoperative Care", "text": "..." }
 *       ]
 *     }
 *   }
 * }
 * ```
 *
 * Chapters (top-level nav groups) are derived from each bone's broad "section" field
 * (e.g. "Shoulder / Arm"). Each entry of a bone's "sections" array becomes its own
 * [SectionEntity], with `type` mapped directly to `sectionType` so the app's
 * Anatomy/Approach/Reduction/Fixation/Pearls/Complications/Rehab filter chips work.
 * Archives from older exports that still ship a single "body" string per bone (no
 * "sections" array) are still supported as a fallback. Every listed figure is copied
 * out of the archive's matching `<boneId>/` folder into local app storage under
 * [IMAGES_DIR_NAME]/<boneId>/.
 */
class BoneDatabaseImporter(
    private val context: Context,
    private val database: OrthoDatabase
) {

    suspend fun importZip(uri: Uri): Result<ImportSummary> = withContext(Dispatchers.IO) {
        val stagingDir = File(context.cacheDir, "db_import_staging")
        try {
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            stagingDir.mkdirs()

            val extracted = extractZip(uri, stagingDir)
            if (!extracted) {
                return@withContext Result.failure(
                    IllegalArgumentException("Could not read the selected file as a .zip archive.")
                )
            }

            val bonesJsonFile = findBonesJson(stagingDir)
                ?: return@withContext Result.failure(
                    IllegalArgumentException("The archive is missing a bones.json file.")
                )

            val root = try {
                JSONObject(bonesJsonFile.readText())
            } catch (e: Exception) {
                return@withContext Result.failure(
                    IllegalArgumentException("bones.json is not valid JSON.")
                )
            }

            // Parsed defensively: a malformed or missing translation must never
            // fail an import that would otherwise succeed in English.
            val frenchBones: JSONObject? = findFrenchBonesJson(stagingDir)
                ?.let { file -> runCatching { JSONObject(file.readText()) }.getOrNull() }
                ?.optJSONObject("bones")

            val bonesObj = root.optJSONObject("bones")
                ?: return@withContext Result.failure(
                    IllegalArgumentException("Invalid database file: missing required \"bones\" object.")
                )

            val chaptersBySection = LinkedHashMap<String, ChapterEntity>()
            val bones = mutableListOf<BoneEntity>()
            val sections = mutableListOf<SectionEntity>()
            val images = mutableListOf<ImageEntity>()

            val permanentImagesDir = File(context.filesDir, IMAGES_DIR_NAME)
            permanentImagesDir.mkdirs()

            var boneIndex = 0
            var figureCount = 0

            val boneKeys = bonesObj.keys()
            while (boneKeys.hasNext()) {
                val key = boneKeys.next()
                val boneObj = bonesObj.optJSONObject(key) ?: continue
                // The two files share keys and structure, so the same key is the
                // whole of the matching logic.
                val frenchObj = frenchBones?.optJSONObject(key)
                val frenchSections = frenchObj?.optJSONArray("sections")
                boneIndex++

                val boneId = slug(boneObj.optString("id", key).ifBlank { key })
                val boneName = boneObj.optString("name").ifBlank {
                    key.replace('-', ' ').replaceFirstChar { it.uppercase() }
                }
                val region = boneObj.optString("region").ifBlank { "General" }
                val sectionLabel = boneObj.optString("section").ifBlank { region }
                val notes = boneObj.optString("notes", "")
                val pageStart = boneObj.optInt("pageStart", -1)
                val pageEnd = boneObj.optInt("pageEnd", -1)
                val sectionsArray = boneObj.optJSONArray("sections")
                val fallbackBody = boneObj.optString("body", "") // legacy archives without "sections"

                // Same rule as the English label: "section", or the region when there is none.
                val frenchSectionLabel = frenchObj?.optString("section")?.takeIf { it.isNotBlank() }
                    ?: frenchObj?.optString("region")?.takeIf { it.isNotBlank() }
                var chapter = chaptersBySection.getOrPut(sectionLabel) {
                    ChapterEntity(
                        id = slug("chap_$sectionLabel"),
                        title = sectionLabel,
                        description = "Imported clinical reference data",
                        icon = "medical_services",
                        orderIndex = chaptersBySection.size + 1,
                        titleFr = frenchSectionLabel,
                        descriptionFr = "Données de référence cliniques importées"
                    )
                }
                // The first bone of a chapter may lack a translation while a later one has it.
                if (chapter.titleFr == null && frenchSectionLabel != null) {
                    chapter = chapter.copy(titleFr = frenchSectionLabel)
                    chaptersBySection[sectionLabel] = chapter
                }
                val boneNameFr = frenchObj?.optString("name")?.takeIf { it.isNotBlank() }

                bones.add(
                    BoneEntity(
                        id = boneId,
                        chapterId = chapter.id,
                        name = boneName,
                        subtitle = notes.ifBlank { fallbackBody.take(140) },
                        region = region,
                        traumaType = "",
                        classificationSummary = notes,
                        nameFr = boneNameFr,
                        subtitleFr = frenchObj?.optString("notes")?.takeIf { it.isNotBlank() },
                        regionFr = frenchObj?.optString("region")?.takeIf { it.isNotBlank() },
                        classificationSummaryFr = frenchObj?.optString("notes")
                            ?.takeIf { it.isNotBlank() },
                        orderIndex = boneIndex,
                        isHighYield = false
                    )
                )

                // Each entry of the "sections" array becomes its own SectionEntity so the
                // Anatomy/Approach/Reduction/Fixation/Pearls/Complications/Rehab filter chips
                // in Bone Detail can match sections by sectionType.
                if (sectionsArray != null && sectionsArray.length() > 0) {
                    for (s in 0 until sectionsArray.length()) {
                        val secObj = sectionsArray.optJSONObject(s) ?: continue
                        val type = secObj.optString("type", "OVERVIEW").ifBlank { "OVERVIEW" }
                        val heading = secObj.optString("heading", type)
                        val text = secObj.optString("text", "")
                        if (text.isBlank()) continue

                        // Sections are matched by index: the files are stated to
                        // share structure, so entry s in one is entry s in the
                        // other. A shorter French array simply leaves the tail
                        // untranslated rather than mispairing.
                        val frenchSec = frenchSections?.optJSONObject(s)

                        sections.add(
                            SectionEntity(
                                id = "${boneId}_sec_$s",
                                boneId = boneId,
                                sectionType = type,
                                title = heading,
                                content = text,
                                pearls = "",
                                warnings = "",
                                titleFr = frenchSec?.optString("heading")
                                    ?.takeIf { it.isNotBlank() },
                                contentFr = frenchSec?.optString("text")
                                    ?.takeIf { it.isNotBlank() },
                                orderIndex = s + 1
                            )
                        )
                    }
                } else if (fallbackBody.isNotBlank()) {
                    // Backward compatibility with older archives that still ship a single
                    // "body" string per bone instead of a pre-split "sections" array.
                    val pageRange = if (pageStart >= 0 && pageEnd >= 0) "Pages $pageStart-$pageEnd" else "Reference"
                    sections.add(
                        SectionEntity(
                            id = "${boneId}_body",
                            boneId = boneId,
                            sectionType = pageRange,
                            title = boneName,
                            content = fallbackBody,
                            pearls = "",
                            warnings = "",
                            orderIndex = 1
                        )
                    )
                }

                // The bone's own subfolder in the archive (named after its bones.json key)
                // holds the jpg figures referenced by "file".
                val boneSourceDir = File(stagingDir, key).let { primary ->
                    if (primary.exists()) primary else File(stagingDir, boneId)
                }
                val boneImagesOutDir = File(permanentImagesDir, boneId)

                val figuresArray = boneObj.optJSONArray("figures")
                if (figuresArray != null) {
                    for (f in 0 until figuresArray.length()) {
                        val figObj = figuresArray.optJSONObject(f) ?: continue
                        val fileName = figObj.optString("file", "")
                        if (fileName.isBlank()) continue

                        val sourceFile = File(boneSourceDir, fileName)
                        if (!sourceFile.exists()) continue

                        boneImagesOutDir.mkdirs()
                        val destFile = File(boneImagesOutDir, fileName)
                        try {
                            sourceFile.copyTo(destFile, overwrite = true)
                        } catch (e: Exception) {
                            continue
                        }

                        val label = figObj.optString("label", "Fig ${f + 1}")
                        images.add(
                            ImageEntity(
                                id = "${boneId}_fig_${slug(fileName)}",
                                boneId = boneId,
                                sectionId = null,
                                title = label,
                                description = "$boneName • Fig $label",
                                descriptionFr = "${boneNameFr ?: boneName} • Fig. $label",
                                drawableResName = "",
                                imagePath = destFile.absolutePath,
                                figureNumber = label
                            )
                        )
                        figureCount++
                    }
                }
            }

            if (bones.isEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("No bones found in bones.json.")
                )
            }

            database.chapterDao().insertChapters(chaptersBySection.values.toList())
            database.boneDao().insertBones(bones)
            if (sections.isNotEmpty()) database.contentDao().insertSections(sections)
            if (images.isNotEmpty()) database.imageDao().insertImages(images)

            Result.success(
                ImportSummary(
                    chapterCount = chaptersBySection.size,
                    boneCount = bones.size,
                    figureCount = figureCount
                )
            )
        } catch (e: OutOfMemoryError) {
            Result.failure(IllegalArgumentException("The archive is too large to import on this device."))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    private fun extractZip(uri: Uri, destDir: File): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zis ->
                    var any = false
                    var entry = zis.nextEntry
                    while (entry != null) {
                        any = true
                        val outFile = File(destDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                    any
                }
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun findBonesJson(root: File): File? = findJson(root) { it == "bones.json" }

    /**
     * The French companion file, if the archive ships one.
     *
     * Several spellings are accepted because the file is maintained by hand and
     * "bonesFR.json", "bones_fr.json" and "bones-fr.json" are all plausible.
     * Absent is not an error: an English-only archive imports exactly as before.
     */
    private fun findFrenchBonesJson(root: File): File? = findJson(root) { name ->
        name in setOf("bonesfr.json", "bones_fr.json", "bones-fr.json", "bones.fr.json")
    }

    private fun findJson(root: File, matches: (String) -> Boolean): File? {
        if (!root.exists()) return null
        root.listFiles()?.firstOrNull { it.isFile && matches(it.name.lowercase()) }
            ?.let { return it }

        // Fallback in case the archive nests everything under a single top-level folder.
        root.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                dir.listFiles()?.firstOrNull { it.isFile && matches(it.name.lowercase()) }
                    ?.let { return it }
            }
        }
        return null
    }

    private fun slug(input: String): String =
        input.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "item" }

    companion object {
        const val IMAGES_DIR_NAME = "imported_figures"
    }
}
