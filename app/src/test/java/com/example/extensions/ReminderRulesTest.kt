package com.example.extensions

import com.example.data.reminders.ExtensionReminderStore
import com.example.data.reminders.ReminderRules
import com.example.data.reminders.StoredReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/** Reminders of `OrthoTips.notifications`: timing, validation, limit, replace. Robolectric only supplies org.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReminderRulesTest {

    private val paris = TimeZone.getTimeZone("Europe/Paris")
    private fun ms(zone: String, y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()
    private fun local(zone: String, epoch: Long) =
        java.time.Instant.ofEpochMilli(epoch).atZone(ZoneId.of(zone)).toLocalDateTime()

    @Test
    fun dailyReminderKeeps0900AcrossDaylightSaving() {
        // 2026-03-29: France moves from UTC+1 to UTC+2.
        val anchor = ms("Europe/Paris", 2026, 3, 27, 9, 0)
        var at = anchor
        for (day in 1..4) {
            at = ReminderRules.nextAfter(anchor, 1, at, paris)
            assertEquals("day $day", LocalDateTime.of(2026, 3, 27 + day, 9, 0), local("Europe/Paris", at))
            // Same answer as ZonedDateTime.plusDays, the reference of the spec.
            assertEquals(LocalDateTime.of(2026, 3, 27, 9, 0).atZone(ZoneId.of("Europe/Paris")).plusDays(day.toLong()).toInstant().toEpochMilli(), at)
        }
    }

    @Test
    fun catchingUpAfterDaysOffFiresOnce() {
        val anchor = ms("Europe/Paris", 2026, 3, 1, 9, 0)
        val phoneBackOn = ms("Europe/Paris", 2026, 3, 6, 14, 30)   // five days later, after 09:00
        val next = ReminderRules.nextAfter(anchor, 1, phoneBackOn, paris)
        assertEquals(LocalDateTime.of(2026, 3, 7, 9, 0), local("Europe/Paris", next))  // one time, the next 09:00
        val weekly = ReminderRules.nextAfter(anchor, 7, phoneBackOn, paris)
        assertEquals(LocalDateTime.of(2026, 3, 8, 9, 0), local("Europe/Paris", weekly))
    }

    @Test
    fun aTimeMovedByTheDstGapDoesNotDrift() {
        // 02:30 does not exist on 2026-03-29 in Paris; the next day is 02:30 again.
        val anchor = ms("Europe/Paris", 2026, 3, 28, 2, 30)
        val gapDay = ReminderRules.nextAfter(anchor, 1, anchor, paris)
        val after = ReminderRules.nextAfter(anchor, 1, gapDay, paris)
        assertEquals(LocalDateTime.of(2026, 3, 30, 2, 30), local("Europe/Paris", after))
    }

    @Test
    fun invalidInputIsRejectedWithAClearMessage() {
        fun rejects(expected: String, block: () -> Unit) {
            try { block(); fail("accepted: $expected") } catch (e: IllegalArgumentException) {
                assertTrue("'${e.message}' should mention $expected", e.message!!.contains(expected))
            }
        }
        rejects("Invalid notification id") { ReminderRules.validate("bad id!", "T", "", 1e12, 0.0, null) }
        rejects("Invalid notification id") { ReminderRules.validate("x".repeat(65), "T", "", 1e12, 0.0, null) }
        rejects("Invalid notification title") { ReminderRules.validate("a", "", "", 1e12, 0.0, null) }
        rejects("Invalid notification title") { ReminderRules.validate("a", "t".repeat(101), "", 1e12, 0.0, null) }
        rejects("body too long") { ReminderRules.validate("a", "T", "b".repeat(301), 1e12, 0.0, null) }
        rejects("data too long") { ReminderRules.validate("a", "T", "", 1e12, 0.0, "d".repeat(1001)) }
        rejects("Invalid notification time") { ReminderRules.validate("a", "T", "", null, 0.0, null) }
        rejects("Invalid notification time") { ReminderRules.validate("a", "T", "", Double.NaN, 0.0, null) }
        rejects("Invalid repeatDays") { ReminderRules.validate("a", "T", "", 1e12, 366.0, null) }
        rejects("Invalid repeatDays") { ReminderRules.validate("a", "T", "", 1e12, 1.5, null) }
        val ok = ReminderRules.validate("plant:ficus-1", "Arroser le ficus", "", 1.7e12, 3.0, "{\"plant\":1}")
        assertEquals(3, ok.repeatDays)
        assertEquals(1_700_000_000_000L, ok.at)
    }

    @Test
    fun limitOf64PerExtensionAndReplaceById() {
        val store = ExtensionReminderStore(Files.createTempDirectory("rem").resolve("r.json").toFile())
        fun row(ext: String, id: String, at: Long = 1000L) = StoredReminder(ext, id, "T", "", at, 0, null, 0, at)
        for (i in 1..64) store.put(row("mes-plantes", "p$i"))
        try { store.put(row("mes-plantes", "p65")); fail("65th accepted") } catch (e: IllegalArgumentException) {
            assertEquals("Too many notifications (max 64)", e.message)
        }
        store.put(row("mes-plantes", "p1", at = 5000L))            // replacing does not count
        assertEquals(64, store.forExtension("mes-plantes").size)
        assertEquals(5000L, store.get("mes-plantes", "p1")!!.nextAt)
        store.put(row("autre", "p1"))                              // other extensions have their own 64
        assertEquals(1, store.forExtension("autre").size)
        assertTrue(store.remove("mes-plantes", "p2"))
        assertEquals(63, store.removeAll("mes-plantes").size)
        assertEquals(listOf("autre"), store.all().map { it.extensionId })
    }

    @Test
    fun exactFlagIsKeptAndOptional() {
        val file = Files.createTempDirectory("rem").resolve("r.json").toFile()
        val store = ExtensionReminderStore(file)
        store.put(StoredReminder("focus-flow", "timer", "Fin du défi", "", 1000L, 0, null, 0, 1000L, exact = true))
        store.put(StoredReminder("focus-flow", "daily", "Pomodoro", "", 2000L, 1, null, 0, 2000L))
        assertTrue(ExtensionReminderStore(file).get("focus-flow", "timer")!!.exact)          // kept across a restart
        assertEquals(false, store.get("focus-flow", "daily")!!.exact)                          // default: inexact
        assertTrue(store.get("focus-flow", "timer")!!.toPublicJson().getBoolean("exact"))
        // A reminder saved by an older version (no "exact" key) reads as inexact.
        file.writeText("""{"version":1,"reminders":[{"extensionId":"x","id":"a","title":"T","body":"","nextAt":5,"repeatDays":0,"data":null,"createdAt":0,"anchorAt":5}]}""")
        assertEquals(false, ExtensionReminderStore(file).get("x", "a")!!.exact)
    }
}
