package com.example.data.suggestion

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Ranking and matching for the smart-input fields.
 *
 * Deliberately pure Kotlin with no Android or Room dependency, so the behaviour
 * that decides what a surgeon sees mid-operation is unit-testable without a
 * device.
 *
 * What it fixes about the previous behaviour:
 *  - Ordering was `useCount DESC`, so a term entered fifty times two years ago
 *    permanently outranked one used five times last week. Scores now decay.
 *  - Matching was a bare `contains`, which ranked "Fracture of the distal femur"
 *    the same as "Femur" for the query "fem". Prefix and word-start matches now
 *    win.
 *  - A typo returned nothing at all. Close misses are now tolerated, but only
 *    once the query is long enough that a fuzzy hit is not noise.
 */
object SuggestionEngine {

    /** How a candidate matched, best first. The order is the ranking order. */
    enum class MatchKind(val weight: Double) {
        /** The whole value starts with the query: "fem" → "Femur". */
        PREFIX(4.0),

        /** A word inside the value starts with the query: "rad" → "Distal radius". */
        WORD_PREFIX(3.0),

        /** The query appears somewhere: "adius" → "Distal radius". */
        CONTAINS(1.5),

        /** Within a small edit distance: "raduis" → "radius". */
        FUZZY(0.6)
    }

    data class Scored(
        val value: String,
        val kind: MatchKind,
        val score: Double,
        /** Character range of the match in [value], for highlighting. */
        val highlight: IntRange?
    )

    data class Candidate(
        val value: String,
        val useCount: Int,
        val lastUsedAt: Long,
        /** Curated vocabulary shipped with the app, never typed by this user. */
        val isSeed: Boolean = false,
        /** Times this value followed the current context; drives contextual ranking. */
        val contextCount: Int = 0
    )

    /** A use is worth half as much after this long. */
    private const val HALF_LIFE_DAYS = 90.0
    private const val DAY_MILLIS = 24 * 60 * 60 * 1000.0

    /**
     * Recency-weighted familiarity. A seed value scores just above zero so the
     * surgeon's own vocabulary always outranks the shipped list, while an unused
     * seed still beats nothing at all.
     */
    fun familiarity(candidate: Candidate, now: Long): Double {
        if (candidate.isSeed && candidate.useCount == 0) return 0.15
        val ageDays = max(0.0, (now - candidate.lastUsedAt) / DAY_MILLIS)
        val decay = 0.5.pow(ageDays / HALF_LIFE_DAYS)
        // Square root keeps a 100-use term from burying a 10-use one entirely;
        // the difference should be visible, not absolute.
        return kotlin.math.sqrt(candidate.useCount.toDouble()) * (0.25 + 0.75 * decay)
    }

    /**
     * Ranks [candidates] against [query].
     *
     * An empty query is not an empty result: it returns the most familiar values
     * for the field. Most of these fields have a small working vocabulary — a
     * surgeon uses three anaesthesia types, not thirty — so showing the top few
     * the moment a field is focused removes the typing entirely.
     */
    fun rank(
        query: String,
        candidates: List<Candidate>,
        limit: Int = 8,
        now: Long = System.currentTimeMillis()
    ): List<Scored> {
        if (candidates.isEmpty()) return emptyList()
        val normalizedQuery = normalize(query.trim())

        if (normalizedQuery.isEmpty()) {
            return candidates
                .asSequence()
                .sortedByDescending { familiarity(it, now) + it.contextCount * 0.5 }
                .take(limit)
                .map { Scored(it.value, MatchKind.PREFIX, familiarity(it, now), null) }
                .toList()
        }

        return candidates.asSequence()
            .mapNotNull { candidate ->
                val normalizedValue = normalize(candidate.value)
                // An exact match is not a suggestion; it is what is already typed.
                if (normalizedValue == normalizedQuery) return@mapNotNull null
                val match = classify(normalizedValue, normalizedQuery) ?: return@mapNotNull null

                val base = familiarity(candidate, now)
                // Context is a strong signal: a procedure that followed this exact
                // diagnosis before is usually the right one.
                val contextBoost = if (candidate.contextCount > 0) {
                    1.0 + min(2.0, candidate.contextCount * 0.4)
                } else {
                    0.0
                }
                // Shorter values rank higher for the same match kind, so "Femur"
                // beats "Periprosthetic fracture of the femur" on "fem".
                val brevity = 1.0 / (1.0 + normalizedValue.length / 40.0)

                Scored(
                    value = candidate.value,
                    kind = match.first,
                    score = match.first.weight * (1.0 + base) * brevity + contextBoost,
                    highlight = match.second
                )
            }
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.value.length })
            .take(limit)
            .toList()
    }

    private fun classify(value: String, query: String): Pair<MatchKind, IntRange?>? {
        if (value.startsWith(query)) return MatchKind.PREFIX to query.indices

        var index = 0
        while (index < value.length) {
            val space = value.indexOf(' ', index)
            val wordStart = if (index == 0) 0 else index
            if (value.startsWith(query, wordStart)) {
                return MatchKind.WORD_PREFIX to (wordStart until wordStart + query.length)
            }
            if (space < 0) break
            index = space + 1
        }

        val contains = value.indexOf(query)
        if (contains >= 0) return MatchKind.CONTAINS to (contains until contains + query.length)

        // Fuzzy only once the query is substantial. Below four characters almost
        // anything is within one edit, and the list fills with noise.
        if (query.length >= 4) {
            val tolerance = if (query.length >= 7) 2 else 1
            if (value.split(' ').any { word -> editDistanceWithin(word, query, tolerance) }) {
                return MatchKind.FUZZY to null
            }
        }
        return null
    }

    /**
     * Bounded Levenshtein. Returns early once the best possible result exceeds
     * [maxDistance], which keeps this cheap enough to run per keystroke over the
     * whole vocabulary.
     */
    fun editDistanceWithin(a: String, b: String, maxDistance: Int): Boolean {
        if (kotlin.math.abs(a.length - b.length) > maxDistance) return false
        if (a == b) return true

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost
                )
                rowMin = min(rowMin, current[j])
            }
            if (rowMin > maxDistance) return false
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length] <= maxDistance
    }

    /** Case- and accent-insensitive, so "oedème" matches "oedeme" and "Oedeme". */
    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .lowercase()
            .trim()

    /**
     * Cleans a value before it is learned.
     *
     * Trailing punctuation and doubled spaces would otherwise create "Spinal",
     * "Spinal." and "Spinal  " as three separate suggestions for the same thing.
     */
    fun canonicalize(raw: String): String? {
        val cleaned = raw.trim()
            .replace(Regex("\\s+"), " ")
            .trimEnd('.', ',', ';', ':', '-', '\u2022')
            .trim()
        return cleaned.takeIf { it.length in 2..80 }
    }
}
