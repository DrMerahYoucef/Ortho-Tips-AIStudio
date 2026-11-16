package com.example.storage

import com.example.data.suggestion.FieldKind
import com.example.data.suggestion.SuggestionCatalog
import com.example.data.suggestion.SuggestionEngine
import com.example.data.suggestion.SuggestionEngine.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ranking rules are the whole point of the smart-input change, and they are
 * pure Kotlin, so they can be pinned down properly rather than eyeballed on a
 * device.
 */
class SuggestionEngineTest {

    private val now = 1_700_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private fun candidate(value: String, uses: Int = 1, daysAgo: Long = 0) =
        Candidate(value, uses, now - daysAgo * day)

    @Test
    fun `prefix match outranks a mid-word match`() {
        val ranked = SuggestionEngine.rank(
            query = "fem",
            candidates = listOf(
                candidate("Periprosthetic fracture of the femur", uses = 20),
                candidate("Femur", uses = 1)
            ),
            now = now
        )
        assertEquals("Femur", ranked.first().value)
    }

    @Test
    fun `word start beats a match buried mid-word`() {
        val ranked = SuggestionEngine.rank(
            query = "rad",
            candidates = listOf(candidate("Intramedullary"), candidate("Distal radius")),
            now = now
        )
        assertEquals("Distal radius", ranked.first().value)
    }

    @Test
    fun `a recent term outranks a stale one used more often`() {
        val ranked = SuggestionEngine.rank(
            query = "spin",
            candidates = listOf(
                candidate("Spinal anaesthesia", uses = 40, daysAgo = 900),
                candidate("Spinal block", uses = 4, daysAgo = 1)
            ),
            now = now
        )
        assertEquals(
            "Frequency alone should not pin a two-year-old term to the top",
            "Spinal block",
            ranked.first().value
        )
    }

    @Test
    fun `a typo still finds the value`() {
        val ranked = SuggestionEngine.rank(
            query = "raduis",
            candidates = listOf(candidate("Distal radius fracture")),
            now = now
        )
        assertEquals(1, ranked.size)
        assertEquals(SuggestionEngine.MatchKind.FUZZY, ranked.first().kind)
    }

    @Test
    fun `short queries do not fuzzy match, because everything is one edit away`() {
        val ranked = SuggestionEngine.rank(
            query = "abc",
            candidates = listOf(candidate("abd"), candidate("xyz")),
            now = now
        )
        assertTrue("A three-letter query must not pull in near-misses", ranked.isEmpty())
    }

    @Test
    fun `an exact match is not offered back as a suggestion`() {
        val ranked = SuggestionEngine.rank(
            query = "Femur",
            candidates = listOf(candidate("Femur")),
            now = now
        )
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `an empty query returns the most familiar values`() {
        val ranked = SuggestionEngine.rank(
            query = "",
            candidates = listOf(
                candidate("Rarely used", uses = 1, daysAgo = 300),
                candidate("Everyday value", uses = 30, daysAgo = 1)
            ),
            limit = 2,
            now = now
        )
        assertEquals("Everyday value", ranked.first().value)
    }

    @Test
    fun `context makes a correlated value lead`() {
        val ranked = SuggestionEngine.rank(
            query = "o",
            candidates = listOf(
                Candidate("Osteotomy", useCount = 30, lastUsedAt = now),
                Candidate("ORIF volar plate", useCount = 2, lastUsedAt = now, contextCount = 5)
            ),
            now = now
        )
        assertEquals("ORIF volar plate", ranked.first().value)
    }

    @Test
    fun `accents and case are ignored when matching`() {
        val ranked = SuggestionEngine.rank(
            query = "OEDEME",
            candidates = listOf(candidate("Oedème post-opératoire")),
            now = now
        )
        assertEquals(1, ranked.size)
    }

    @Test
    fun `seeds rank below anything the surgeon has actually used`() {
        val seed = Candidate("General anaesthesia", useCount = 0, lastUsedAt = 0, isSeed = true)
        val used = candidate("Spinal anaesthesia", uses = 1, daysAgo = 400)
        assertTrue(
            SuggestionEngine.familiarity(used, now) > SuggestionEngine.familiarity(seed, now)
        )
    }

    @Test
    fun `canonicalize strips trailing punctuation and collapses spaces`() {
        assertEquals("Spinal anaesthesia", SuggestionEngine.canonicalize("  Spinal   anaesthesia. "))
        assertNull("Single characters are noise", SuggestionEngine.canonicalize("x"))
        assertNull(SuggestionEngine.canonicalize("   "))
    }

    @Test
    fun `list fields split on separators and prose fields do not`() {
        val allergies = SuggestionCatalog.itemsToLearn(
            "patient.allergies", "Penicillin, Latex; Iodine"
        )
        assertEquals(listOf("Penicillin", "Latex", "Iodine"), allergies)

        val plan = SuggestionCatalog.itemsToLearn(
            "patient.treatmentPlan", "Cast for six weeks, then physiotherapy."
        )
        assertEquals(
            "Prose must stay whole; splitting it produced sentence fragments",
            listOf("Cast for six weeks, then physiotherapy"),
            plan
        )
    }

    @Test
    fun `very long prose is not learned as a suggestion`() {
        val essay = "step. ".repeat(60)
        assertTrue(SuggestionCatalog.itemsToLearn("surgery.protocol", essay).isEmpty())
    }

    @Test
    fun `catalog knows which field predicts which`() {
        assertEquals("surgery.diagnosis", SuggestionCatalog.predictorFor("surgery.procedure"))
        assertNull(SuggestionCatalog.predictorFor("surgery.hospital"))
        assertEquals(FieldKind.ATOM, SuggestionCatalog.profileFor("surgery.anesthesia").kind)
    }

    @Test
    fun `bounded edit distance rejects far strings cheaply`() {
        assertTrue(SuggestionEngine.editDistanceWithin("radius", "raduis", 2))
        assertTrue(!SuggestionEngine.editDistanceWithin("radius", "humerus", 2))
    }
}
