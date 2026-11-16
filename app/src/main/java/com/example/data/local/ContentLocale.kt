package com.example.data.local

import android.content.Context
import com.example.data.local.entity.BoneEntity
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.ImageEntity
import com.example.data.local.entity.SectionEntity
import com.example.util.AppLocale
import java.util.Locale

/**
 * Which language the reference library should be read in.
 *
 * Held as a single flag rather than threaded through every query. Changing the
 * app language recreates the activity, so from the point of view of any read
 * this is a constant — the flag only moves during a restart, never between two
 * rows of the same list.
 *
 * The alternative was passing a locale into roughly fifteen `Flow` mappings in
 * `OrthoRepository` and every DAO signature, which buys nothing here because the
 * value cannot change while those flows are being collected.
 */
object ContentLocale {

    @Volatile
    private var french: Boolean = false

    val isFrench: Boolean get() = french

    /** Called at startup and after a language change. */
    fun refresh(context: Context) {
        french = when (AppLocale.currentOption(context)) {
            AppLocale.Option.FRENCH -> true
            AppLocale.Option.ENGLISH -> false
            // Following the system means following whatever the device resolved
            // to, which is the same thing the UI strings did.
            AppLocale.Option.SYSTEM ->
                Locale.getDefault().language.equals("fr", ignoreCase = true)
        }
    }
}

/**
 * Returns the French text when the app is in French and a translation exists.
 *
 * A missing or blank translation falls back to English rather than rendering an
 * empty section. A partially translated import is then merely incomplete instead
 * of broken, which matters because the two JSON files are maintained by hand and
 * will drift.
 */
private fun pick(english: String, french: String?): String =
    if (ContentLocale.isFrench) french?.takeIf { it.isNotBlank() } ?: english else english

// ---- Chapters ----
val ChapterEntity.displayTitle: String get() = pick(title, titleFr)
val ChapterEntity.displayDescription: String get() = pick(description, descriptionFr)

// ---- Bones ----
val BoneEntity.displayName: String get() = pick(name, nameFr)
val BoneEntity.displaySubtitle: String get() = pick(subtitle, subtitleFr)
val BoneEntity.displayRegion: String get() = pick(region, regionFr)
val BoneEntity.displayClassificationSummary: String
    get() = pick(classificationSummary, classificationSummaryFr)

// ---- Sections ----
val SectionEntity.displayTitle: String get() = pick(title, titleFr)
val SectionEntity.displayContent: String get() = pick(content, contentFr)
val SectionEntity.displayPearls: String get() = pick(pearls, pearlsFr)
val SectionEntity.displayWarnings: String get() = pick(warnings, warningsFr)

// ---- Figures ----
val ImageEntity.displayTitle: String get() = pick(title, titleFr)
val ImageEntity.displayDescription: String get() = pick(description, descriptionFr)

// ---- Language-resolved copies ----
// The repository hands these to the UI so every screen that reads `.title`,
// `.content`, `.name`… shows the current language without each screen having to
// remember the display* accessors. They are read-only views: never write them
// back to the database, or the English columns would be overwritten.
fun ChapterEntity.localized(): ChapterEntity =
    copy(title = displayTitle, description = displayDescription)

fun BoneEntity.localized(): BoneEntity = copy(
    name = displayName,
    subtitle = displaySubtitle,
    region = displayRegion,
    classificationSummary = displayClassificationSummary
)

fun SectionEntity.localized(): SectionEntity = copy(
    title = displayTitle,
    content = displayContent,
    pearls = displayPearls,
    warnings = displayWarnings
)

fun ImageEntity.localized(): ImageEntity =
    copy(title = displayTitle, description = displayDescription)
