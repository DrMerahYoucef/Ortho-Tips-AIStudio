package com.example.data.local.model

import kotlinx.serialization.Serializable

/**
 * Models for classification reference data, read from installed classification
 * extensions (bundled source: app/src/extensions/classifications/). The app never
 * modifies it; [com.example.data.repository.ClassificationRepository] parses it at runtime.
 *
 * Schema (do not rename fields; must match the pack JSON exactly):
 *
 * index.json:
 * { "chapters": [ { "id": String, "title": String, "file": String, "sectionCount": Int, "classificationCount": Int } ] }
 *
 * data/<chapitre>.json:
 * { "id": String, "title": String, "sections": [ { "id", "title", "classifications": [ ... ] } ] }
 */

@Serializable
data class ClassificationIndex(
    val chapters: List<ChapterRef> = emptyList()
)

@Serializable
data class ChapterRef(
    val id: String,
    val title: String,
    val file: String,
    val sectionCount: Int = 0,
    val classificationCount: Int = 0
)

@Serializable
data class ClassificationChapter(
    val id: String,
    val title: String,
    val sections: List<ClassificationSection> = emptyList()
)

@Serializable
data class ClassificationSection(
    val id: String,
    val title: String,
    val classifications: List<ClassificationEntry> = emptyList()
)

@Serializable
data class ClassificationEntry(
    val id: String,
    val title: String,
    val images: List<String> = emptyList(),
    val blocks: List<ClassificationBlock> = emptyList(),
    /**
     * Rich content written with the classification editor: Word-style paragraphs
     * (see [DocParagraph]). Packs made by the editor use this instead of [blocks]
     * and [images]; the viewer shows whichever is present.
     */
    val content: List<DocParagraph> = emptyList()
)

/**
 * A single content block within a classification entry. [type] is one of "text", "item",
 * or "table" — fields not relevant to that type are simply left at their defaults.
 */
@Serializable
data class ClassificationBlock(
    val type: String,
    val lines: List<String> = emptyList(),
    val label: String? = null,
    val text: String? = null,
    val bullets: List<String> = emptyList(),
    val sub: List<ClassificationSubGroup> = emptyList(),
    val headers: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList()
)

@Serializable
data class ClassificationSubGroup(
    val label: String,
    val bullets: List<String> = emptyList()
)

/**
 * One line of a classification document, as in a word processor: a [style]
 * and, for lists and text, an indent [level]. Any depth of headings and nested
 * lists is expressed with these two fields, so the format never needs a new
 * nesting construct.
 *
 * [style] is a string ([ParagraphStyles]) rather than an enum on purpose: a
 * pack written by a newer app with a style this one does not know still loads,
 * and the unknown line is shown as plain text.
 */
@Serializable
data class DocParagraph(
    val style: String = ParagraphStyles.TEXT,
    val text: String = "",
    /** Heading depth for [ParagraphStyles.HEADING] (1 = subtitle), indent for everything else. */
    val level: Int = 0,
    /** Pack-relative path of the figure for [ParagraphStyles.IMAGE]. */
    val image: String? = null,
    /** Cells for [ParagraphStyles.TABLE]; the first row is the header. */
    val rows: List<List<String>> = emptyList()
)

object ParagraphStyles {
    /** The chapter title; the first one names the chapter. */
    const val TITLE = "title"

    /**
     * Level 1 starts a section (subtitle), level 2 a classification card
     * (sub-subtitle); deeper levels are headings inside a classification.
     */
    const val HEADING = "heading"
    const val TEXT = "text"
    const val NOTE = "note"
    const val BULLET = "bullet"
    const val NUMBERED = "numbered"
    const val LETTERED = "lettered"
    const val IMAGE = "image"
    const val TABLE = "table"

    val LISTS = setOf(BULLET, NUMBERED, LETTERED)
}
