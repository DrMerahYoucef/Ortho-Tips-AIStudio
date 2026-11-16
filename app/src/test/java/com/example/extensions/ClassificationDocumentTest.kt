package com.example.extensions

import com.example.data.classification.ClassificationDocument
import com.example.data.local.model.ClassificationBlock
import com.example.data.local.model.ClassificationChapter
import com.example.data.local.model.ClassificationEntry
import com.example.data.local.model.ClassificationSection
import com.example.data.local.model.ClassificationSubGroup
import com.example.data.local.model.DocParagraph
import com.example.data.local.model.ParagraphStyles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Word-style classification editor's document engine (pure Kotlin). */
class ClassificationDocumentTest {

    @Test
    fun pastedTextKeepsListsIndentsHeadingsAndTables() {
        val p = ClassificationDocument.parsePastedText(
            "Intro\n\n# Épaule\n## Neer\n- 1 fragment\n  - peu déplacée\n1. Type I\n   a) sous-type\nGrade\tDescription\nI\tSimple"
        )
        assertEquals(ParagraphStyles.TEXT, p[0].style)
        assertEquals(ParagraphStyles.HEADING to 1, p[1].style to p[1].level)
        assertEquals(ParagraphStyles.HEADING to 2, p[2].style to p[2].level)
        assertEquals(ParagraphStyles.BULLET to 0, p[3].style to p[3].level)
        assertEquals(ParagraphStyles.BULLET to 1, p[4].style to p[4].level)
        assertEquals("peu déplacée", p[4].text)
        assertEquals(ParagraphStyles.NUMBERED, p[5].style)
        assertEquals(ParagraphStyles.LETTERED to 1, p[6].style to p[6].level)
        assertEquals(listOf(listOf("Grade", "Description"), listOf("I", "Simple")), p[7].rows)
    }

    @Test
    fun markersNumberPerLevelAndRestart() {
        val doc = listOf(
            DocParagraph(ParagraphStyles.NUMBERED, "a"), DocParagraph(ParagraphStyles.NUMBERED, "b"),
            DocParagraph(ParagraphStyles.LETTERED, "c", 1), DocParagraph(ParagraphStyles.LETTERED, "d", 1),
            DocParagraph(ParagraphStyles.NUMBERED, "e"), DocParagraph(ParagraphStyles.LETTERED, "f", 1),
            DocParagraph(ParagraphStyles.HEADING, "h", 3), DocParagraph(ParagraphStyles.NUMBERED, "g")
        )
        assertEquals(listOf("1.", "2.", "a)", "b)", "3.", "a)", null, "1."), ClassificationDocument.listMarkers(doc))
        assertEquals("aa", ClassificationDocument.letters(27))
    }

    @Test
    fun headingsBuildTheStructureAndRoundTripIsLossless() {
        val doc = listOf(
            DocParagraph(ParagraphStyles.TITLE, "Épaule"),
            DocParagraph(ParagraphStyles.HEADING, "Fractures", 1),
            DocParagraph(ParagraphStyles.HEADING, "Neer", 2),
            DocParagraph(ParagraphStyles.BULLET, "1 fragment"),
            DocParagraph(ParagraphStyles.HEADING, "Type IV", 3),
            DocParagraph(ParagraphStyles.BULLET, "détail", 1),
            DocParagraph(ParagraphStyles.IMAGE, image = "images/x.png"),
            DocParagraph(ParagraphStyles.NOTE, "à retenir")
        )
        val chapter = ClassificationDocument.toChapter("epaule", doc)
        assertEquals("Épaule", chapter.title)
        assertEquals("Neer", chapter.sections.single().classifications.single().title)
        assertEquals(listOf("images/x.png"), ClassificationDocument.images(chapter))
        assertEquals(doc, ClassificationDocument.fromChapter(chapter))
    }

    @Test
    fun editedLegacyClassificationsOfferTheSamePatientChoices() {
        val legacy = ClassificationChapter(
            "genou", "Genou", listOf(
                ClassificationSection(
                    "s", "Fractures", listOf(
                        ClassificationEntry(
                            "e", "Schatzker", images = listOf("images/a.png"), blocks = listOf(
                                ClassificationBlock("item", label = "Groupe A", text = "Simples", bullets = listOf("b1"),
                                    sub = listOf(ClassificationSubGroup("A I", listOf("spiroïde")))),
                                ClassificationBlock("table", headers = listOf("H1", "H2"), rows = listOf(listOf("a", "b")))
                            )
                        )
                    )
                )
            )
        )
        val edited = ClassificationDocument.toChapter("genou", ClassificationDocument.fromChapter(legacy))
        val entry = edited.sections.single().classifications.single()
        assertEquals(listOf("Groupe A" to "Simples", "A I" to null), ClassificationDocument.pickerChoices(entry.content))
        assertEquals(listOf("images/a.png"), ClassificationDocument.images(edited))
        assertTrue(entry.content.any { it.style == ParagraphStyles.TABLE && it.rows.size == 2 })
    }
}
