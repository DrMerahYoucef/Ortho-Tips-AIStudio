package com.example.ui

import com.example.ui.components.PickerTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** The appointment date/time pickers must never shift the day or lose the time. */
class PickerTimeTest {
    private lateinit var saved: TimeZone

    @Before fun setUp() { saved = TimeZone.getDefault() }
    @After fun tearDown() { TimeZone.setDefault(saved) }

    private fun local(y: Int, m: Int, d: Int, h: Int, min: Int) =
        Calendar.getInstance().apply { clear(); set(y, m, d, h, min) }.timeInMillis

    @Test fun datePickKeepsTimeOfDay_westAndEast() {
        for (zone in listOf("America/Los_Angeles", "Europe/Paris", "Pacific/Auckland")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            val appointment = local(2026, Calendar.MARCH, 10, 14, 30)
            // Round trip: the picker shows the same local day.
            val pickerValue = PickerTime.toPickerUtc(appointment)
            assertEquals(zone, appointment, PickerTime.withDate(appointment, pickerValue))
            // Picking another day keeps 14:30.
            val moved = PickerTime.withDate(appointment, PickerTime.toPickerUtc(local(2026, Calendar.MARCH, 12, 0, 0)))
            assertEquals(zone, local(2026, Calendar.MARCH, 12, 14, 30), moved)
        }
    }

    @Test fun timePickKeepsDay() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
        val appointment = local(2026, Calendar.OCTOBER, 25, 9, 0) // DST change day
        assertEquals(local(2026, Calendar.OCTOBER, 25, 16, 45), PickerTime.withTime(appointment, 16, 45))
    }
}
