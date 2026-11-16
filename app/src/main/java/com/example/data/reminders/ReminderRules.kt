package com.example.data.reminders

import java.util.Calendar
import java.util.TimeZone

/**
 * The rules of `OrthoTips.notifications` (extension guide, section 5), with no
 * Android dependency so they are unit-tested on the JVM.
 */
object ReminderRules {

    const val MAX_PER_EXTENSION = 64
    const val MAX_TITLE = 100
    const val MAX_BODY = 300
    const val MAX_DATA = 1000
    const val MAX_REPEAT_DAYS = 365

    private val ID = Regex("[A-Za-z0-9._:-]{1,64}")
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** A reminder as an extension asked for it, already validated. */
    data class Request(
        val id: String,
        val title: String,
        val body: String,
        val at: Long,
        val repeatDays: Int,
        val data: String?
    )

    /**
     * Checks what `schedule(…)` received. Throws [IllegalArgumentException] with
     * the message the extension sees in its rejected promise.
     */
    fun validate(id: String?, title: String?, body: String?, at: Double?, repeatDays: Double?, data: String?): Request {
        if (id == null || !ID.matches(id)) throw IllegalArgumentException("Invalid notification id (1–64 characters: letters, digits, . _ : -)")
        if (title == null || title.isEmpty() || title.length > MAX_TITLE) throw IllegalArgumentException("Invalid notification title (1–$MAX_TITLE characters)")
        val text = body.orEmpty()
        if (text.length > MAX_BODY) throw IllegalArgumentException("Notification body too long (max $MAX_BODY characters)")
        if (data != null && data.length > MAX_DATA) throw IllegalArgumentException("Notification data too long (max $MAX_DATA characters)")
        if (at == null || at.isNaN() || at.isInfinite() || at <= 0.0) throw IllegalArgumentException("Invalid notification time: 'at' must be epoch milliseconds")
        val days = repeatDays ?: 0.0
        if (days.isNaN() || days < 0 || days > MAX_REPEAT_DAYS || days != Math.floor(days)) {
            throw IllegalArgumentException("Invalid repeatDays (whole number 0–$MAX_REPEAT_DAYS)")
        }
        return Request(id, title, text, at.toLong(), days.toInt(), data)
    }

    /** 64 per extension; replacing an existing id never counts against it. */
    fun checkLimit(existingIds: Collection<String>, newId: String) {
        if (newId !in existingIds && existingIds.size >= MAX_PER_EXTENSION) {
            throw IllegalArgumentException("Too many notifications (max $MAX_PER_EXTENSION)")
        }
    }

    /**
     * The next time a repeating reminder fires after [after]: [anchor] plus a
     * whole number of [repeatDays]-day steps, each step keeping the anchor's
     * local wall-clock time in [zone] (09:00 stays 09:00 across daylight-saving
     * changes). Always computed from the anchor, so a time moved by a DST gap
     * one day does not drift on the next ones. Catching up after days off
     * returns one time, the first one after [after].
     */
    fun nextAfter(anchor: Long, repeatDays: Int, after: Long, zone: TimeZone): Long {
        require(repeatDays > 0) { "repeatDays must be > 0" }
        // Start near the answer, then step: DST shifts are at most a few hours.
        var steps = maxOf(1L, (after - anchor) / (repeatDays * DAY_MS) - 1)
        var candidate = plusDays(anchor, steps * repeatDays, zone)
        while (candidate <= after) {
            steps++
            candidate = plusDays(anchor, steps * repeatDays, zone)
        }
        return candidate
    }

    /** [epochMs] moved by [days] calendar days at the same local wall-clock time. */
    fun plusDays(epochMs: Long, days: Long, zone: TimeZone): Long {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = epochMs
        var left = days
        while (left != 0L) {
            val chunk = left.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            cal.add(Calendar.DAY_OF_MONTH, chunk)
            left -= chunk
        }
        return cal.timeInMillis
    }

    /** Stable Android notification id for one reminder, so a repeat replaces the previous one. */
    fun notificationId(extensionId: String, id: String): Int = "$extensionId:$id".hashCode()
}
