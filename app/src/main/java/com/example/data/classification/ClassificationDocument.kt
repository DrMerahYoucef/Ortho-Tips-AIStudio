package com.example.data.classification

import com.example.data.local.model.ClassificationBlock
import com.example.data.local.model.ClassificationChapter
import com.example.data.local.model.ClassificationEntry
import com.example.data.local.model.ClassificationSection
import com.example.data.local.model.DocParagraph
import com.example.data.local.model.ParagraphStyles
import java.text.Normalizer

/**
 * The classification editor works like a word processor: a document is a flat
 * list of [DocParagraph]s, each with a style and a level. This object turns
 * pasted text into paragraphs, and converts between paragraphs and the
 * [ClassificationChapter] structure the viewer and the patient picker read.
 *
 * Structure is carried by headings, as in Word's outline view:
 *
 *     Title                → the chapter's name
 *     Heading 1 (subtitle) → a section
 *     Heading 2            → a classification (one card in the viewer)
 *     Heading 3, 4, …      → headings inside a classification
 *     everything else      → the classification's content
 *
 * Nothing constrains depth: headings go as deep as needed and lists nest by
 * indent level. Pure Kotlin, so it is tested on the JVM.
 */
object ClassificationDocument {

    const val SECTION_LEVEL = 1
    const val CLASSIFICATION_LEVEL = 2

    // ---- pasted text ----------------------------------------------------------

    private val BULLET = Regex("^[-•*–—▪◦·●○■□➢➤►]\\s+")
    private val NUMBERED = Regex("^\\d{1,3}[.)]\\s+")
    private val LETTERED = Regex("^[A-Za-z][.)]\\s+")
    private val MARKDOWN_HEADING = Regex("^(#{1,6})\\s+")

    /**
     * Splits pasted text into paragraphs, recognising what a pasted document
     * usually carries: list markers (`-`, `•`, `1.`, `a)`), indentation (a tab
     * or two spaces per level), `#` headings, and tab-separated rows, which
     * become a table. Blank lines are dropped; everything else is text, ready
     * to be restyled.
     */
    fun parsePastedText(text: String): List<DocParagraph> {
        val result = mutableListOf<DocParagraph>()
        var tableRows = mutableListOf<List<String>>()

        fun flushTable() {
            if (tableRows.isEmpty()) return
            result += if (tableRows.size == 1) {
                DocParagraph(ParagraphStyles.TEXT, tableRows.single().joinToString("  "))
            } else {
                DocParagraph(ParagraphStyles.TABLE, rows = normalizeRows(tableRows))
            }
            tableRows = mutableListOf()
        }

        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val line = raw.trimEnd().replace('\u00A0', ' ')
            if (line.isBlank()) {
                flushTable()
                continue
            }
            val body = line.trimStart()
            val indent = indentOf(line.substring(0, line.length - body.length))

            if (body.contains('\t')) {
                tableRows += body.split('\t').map { it.trim() }
                continue
            }
            flushTable()

            val heading = MARKDOWN_HEADING.find(body)
            result += when {
                heading != null -> DocParagraph(
                    ParagraphStyles.HEADING,
                    body.substring(heading.range.last + 1).trim(),
                    heading.groupValues[1].length
                )
                BULLET.containsMatchIn(body) -> DocParagraph(ParagraphStyles.BULLET, body.replaceFirst(BULLET, ""), indent)
                NUMBERED.containsMatchIn(body) -> DocParagraph(ParagraphStyles.NUMBERED, body.replaceFirst(NUMBERED, ""), indent)
                LETTERED.containsMatchIn(body) -> DocParagraph(ParagraphStyles.LETTERED, body.replaceFirst(LETTERED, ""), indent)
                else -> DocParagraph(ParagraphStyles.TEXT, body, indent)
            }
        }
        flushTable()
        return result
    }

    private fun indentOf(whitespace: String): Int {
        var columns = 0
        whitespace.forEach { columns += if (it == '\t') 2 else 1 }
        return (columns / 2).coerceAtMost(MAX_INDENT)
    }

    private fun normalizeRows(rows: List<List<String>>): List<List<String>> {
        val width = rows.maxOf { it.size }
        return rows.map { row -> row + List(width - row.size) { "" } }
    }

    // ---- paragraphs ⇄ chapter ---------------------------------------------------

    /** Builds the chapter the viewer reads. [id] is the chapter's id in its pack. */
    fun toChapter(id: String, paragraphs: List<DocParagraph>): ClassificationChapter {
        val title = paragraphs.firstOrNull { it.style == ParagraphStyles.TITLE }?.text?.trim().orEmpty()
        val sections = mutableListOf<MutableSection>()
        val ids = UniqueIds()

        fun currentSection(): MutableSection =
            sections.lastOrNull() ?: MutableSection(ids.next("section", ""), "").also { sections += it }

        fun currentEntry(): MutableEntry {
            val section = currentSection()
            return section.entries.lastOrNull()
                ?: MutableEntry(ids.next("classification", ""), "").also { section.entries += it }
        }

        var titleSeen = false
        for (p in paragraphs) {
            when {
                p.style == ParagraphStyles.TITLE && !titleSeen -> titleSeen = true
                p.style == ParagraphStyles.TITLE -> currentEntry().content += p.copy(style = ParagraphStyles.HEADING, level = CLASSIFICATION_LEVEL + 1)
                p.style == ParagraphStyles.HEADING && p.level <= SECTION_LEVEL ->
                    sections += MutableSection(ids.next("section", p.text), p.text.trim())
                p.style == ParagraphStyles.HEADING && p.level == CLASSIFICATION_LEVEL ->
                    currentSection().entries += MutableEntry(ids.next("classification", p.text), p.text.trim())
                p.style == ParagraphStyles.TEXT && p.text.isBlank() -> Unit
                else -> currentEntry().content += p
            }
        }
        return ClassificationChapter(
            id = id,
            title = title,
            sections = sections.map { section ->
                ClassificationSection(
                    id = section.id,
                    title = section.title,
                    classifications = section.entries.map { ClassificationEntry(it.id, it.title, content = it.content.toList()) }
                )
            }
        )
    }

    /**
     * Opens any chapter in the editor, including those written before the
     * editor existed: their blocks become headings, text and bullet lines, so
     * the whole reference can be edited the same way.
     */
    fun fromChapter(chapter: ClassificationChapter): List<DocParagraph> = buildList {
        add(DocParagraph(ParagraphStyles.TITLE, chapter.title))
        for (section in chapter.sections) {
            if (section.title.isNotBlank()) add(DocParagraph(ParagraphStyles.HEADING, section.title, SECTION_LEVEL))
            for (entry in section.classifications) {
                if (entry.title.isNotBlank()) add(DocParagraph(ParagraphStyles.HEADING, entry.title, CLASSIFICATION_LEVEL))
                entry.images.forEach { add(DocParagraph(ParagraphStyles.IMAGE, image = it)) }
                entry.blocks.forEach { addAll(fromBlock(it)) }
                addAll(entry.content)
            }
        }
    }

    private fun fromBlock(block: ClassificationBlock): List<DocParagraph> = buildList {
        val labelLevel = CLASSIFICATION_LEVEL + 1
        when (block.type) {
            "text" -> block.lines.forEach { add(DocParagraph(ParagraphStyles.TEXT, it)) }
            "table" -> add(DocParagraph(ParagraphStyles.TABLE, rows = listOf(block.headers).filter { it.isNotEmpty() } + block.rows))
            else -> {
                block.label?.takeIf { it.isNotBlank() }?.let { add(DocParagraph(ParagraphStyles.HEADING, it, labelLevel)) }
                block.text?.takeIf { it.isNotBlank() }?.let { add(DocParagraph(ParagraphStyles.TEXT, it)) }
                // Indented, as the viewer always showed them; like before, they
                // are descriptions rather than choices in the patient picker.
                block.bullets.forEach { add(DocParagraph(ParagraphStyles.BULLET, it, 1)) }
                block.sub.forEach { sub ->
                    add(DocParagraph(ParagraphStyles.HEADING, sub.label, labelLevel + 1))
                    sub.bullets.forEach { add(DocParagraph(ParagraphStyles.BULLET, it, 1)) }
                }
            }
        }
    }

    // ---- presentation helpers ----------------------------------------------------

    /**
     * The marker shown before each paragraph: `•`/`◦`/`▪` for bullets by
     * depth, `1.` for numbered and `a)` for lettered items, null otherwise.
     * Numbering counts per indent level, restarts under a deeper level each
     * time a shallower item appears, and restarts after a heading.
     */
    fun listMarkers(paragraphs: List<DocParagraph>): List<String?> {
        val counters = HashMap<Int, Int>()
        return paragraphs.map { p ->
            when (p.style) {
                in ParagraphStyles.LISTS -> {
                    counters.keys.filter { it > p.level }.forEach { counters.remove(it) }
                    when (p.style) {
                        ParagraphStyles.BULLET -> {
                            counters.remove(p.level)
                            BULLETS[p.level % BULLETS.size]
                        }
                        else -> {
                            val n = (counters[p.level] ?: 0) + 1
                            counters[p.level] = n
                            if (p.style == ParagraphStyles.NUMBERED) "$n." else "${letters(n)})"
                        }
                    }
                }
                ParagraphStyles.HEADING, ParagraphStyles.TITLE -> {
                    counters.clear()
                    null
                }
                else -> {
                    counters.keys.filter { it >= p.level }.forEach { counters.remove(it) }
                    null
                }
            }
        }
    }

    /** a, b, … z, aa, ab, … */
    fun letters(n: Int): String {
        var value = n
        val out = StringBuilder()
        while (value > 0) {
            value--
            out.append('a' + value % 26)
            value /= 26
        }
        return out.reverse().toString()
    }

    /**
     * What the patient/surgery picker offers from editor content: headings
     * inside a classification (its types or grades), with the text line right
     * after a heading as its detail, and top-level list items.
     */
    fun pickerChoices(content: List<DocParagraph>): List<Pair<String, String?>> =
        content.mapIndexedNotNull { index, p ->
            when {
                p.text.isBlank() -> null
                p.style == ParagraphStyles.HEADING -> {
                    val next = content.getOrNull(index + 1)
                    p.text.trim() to next?.takeIf { it.style == ParagraphStyles.TEXT && it.text.isNotBlank() }?.text?.trim()
                }
                p.style in ParagraphStyles.LISTS && p.level == 0 -> p.text.trim() to null
                else -> null
            }
        }

    /** Every figure a chapter refers to, old and new format alike. */
    fun images(chapter: ClassificationChapter): List<String> =
        chapter.sections.flatMap { it.classifications }.flatMap { entry ->
            entry.images + entry.content.mapNotNull { if (it.style == ParagraphStyles.IMAGE) it.image else null }
        }

    fun slug(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(40)

    const val MAX_INDENT = 12
    private val BULLETS = listOf("•", "◦", "▪")

    private class MutableSection(val id: String, val title: String) {
        val entries = mutableListOf<MutableEntry>()
    }

    private class MutableEntry(val id: String, val title: String) {
        val content = mutableListOf<DocParagraph>()
    }

    private class UniqueIds {
        private val used = HashSet<String>()
        fun next(prefix: String, title: String): String {
            val base = slug(title).ifEmpty { prefix }
            var id = base
            var n = 2
            while (!used.add(id)) id = "${base}_${n++}"
            return id
        }
    }
}
