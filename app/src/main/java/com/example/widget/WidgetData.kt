package com.example.widget

import android.content.Context
import com.example.data.local.OrthoDatabase
import com.example.data.local.entity.Attendance
import com.example.data.repository.ShiftRepository
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One line in the widget list. */
data class WidgetRow(
    val title: String,
    val subtitle: String,
    val accent: Accent,
    val targetPatientId: String? = null,
    val eventId: String? = null,
    /** Today's appointments carry the came / missed / note buttons. */
    val actionable: Boolean = false
) {
    enum class Accent { CURRENT, NEXT, LATER, NEUTRAL, ATTENDED, MISSED }
}

/**
 * Reads the two lists the widget shows from the local Room database.
 */
object WidgetData {
    // Both entry points are handed a context already wrapped in the app's chosen
    // language, so getString and the date formats resolve there.

    suspend fun loadShifts(context: Context, locale: Locale): List<WidgetRow> {
        val dao = OrthoDatabase.getInstance(context).shiftDao()
        val today = ShiftRepository.today()
        val upcoming = dao.getUpcoming(today, 15)
        val dayFormat = SimpleDateFormat("EEEE d MMMM", locale)
        // Red for the shift running today, amber for the very next one, grey after that.
        val nextDate = upcoming.firstOrNull { it.date != today }?.date
        return upcoming.map { shift ->
            val millis = ShiftRepository.parse(shift.date)
            val isToday = shift.date == today
            WidgetRow(
                title = dayFormat.format(Date(millis)).replaceFirstChar { it.titlecase(locale) },
                subtitle = relativeLabel(context, millis, isToday),
                accent = when {
                    isToday -> WidgetRow.Accent.CURRENT
                    shift.date == nextDate -> WidgetRow.Accent.NEXT
                    else -> WidgetRow.Accent.LATER
                }
            )
        }
    }

    /** Events of [dayOffset] days from today: 0 = today, 1 = tomorrow. */
    suspend fun loadEvents(context: Context, locale: Locale, dayOffset: Int): List<WidgetRow> {
        val dao = OrthoDatabase.getInstance(context).timelineEventDao()
        val start = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val events = dao.getEventsWithPatientBetween(start.timeInMillis, end.timeInMillis - 1)
        val timeFormat = SimpleDateFormat("HH:mm", locale)
        return events.map { event ->
            val marks = when (event.attendance) {
                Attendance.ATTENDED.name -> context.getString(com.example.R.string.widget_attended)
                Attendance.MISSED.name -> context.getString(com.example.R.string.widget_missed)
                else -> null
            }
            WidgetRow(
                title = "${event.patientName} — ${event.title}",
                subtitle = listOfNotNull(
                    timeFormat.format(Date(event.eventDate)),
                    marks,
                    event.remark.takeIf { it.isNotBlank() }?.take(50)
                        ?: event.description.takeIf { it.isNotBlank() }?.take(50)
                ).joinToString(" · "),
                accent = when (event.attendance) {
                    Attendance.ATTENDED.name -> WidgetRow.Accent.ATTENDED
                    Attendance.MISSED.name -> WidgetRow.Accent.MISSED
                    else -> WidgetRow.Accent.NEUTRAL
                },
                targetPatientId = event.patientId,
                eventId = event.id,
                actionable = dayOffset == 0
            )
        }
    }

    /** Blocking entry points for legacy RemoteViewsFactory (API < 31). */
    fun shifts(context: Context, locale: Locale): List<WidgetRow> =
        runBlocking { loadShifts(context, locale) }

    fun events(context: Context, locale: Locale, dayOffset: Int): List<WidgetRow> =
        runBlocking { loadEvents(context, locale, dayOffset) }

    /** Header for the events page, e.g. "Today · Friday 18 September". */
    fun dayLabel(context: Context, locale: Locale, dayOffset: Int): String {
        val calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, dayOffset) }
        val name = context.getString(
            if (dayOffset == 0) com.example.R.string.widget_today_label else com.example.R.string.widget_tomorrow
        )
        val date = SimpleDateFormat("EEEE d MMMM", locale).format(calendar.time)
        return "$name · $date"
    }

    private fun relativeLabel(context: Context, millis: Long, isToday: Boolean): String {
        if (isToday) return context.getString(com.example.R.string.widget_today)
        val days = ((millis - startOfToday()) / 86_400_000L).toInt()
        return if (days == 1) context.getString(com.example.R.string.widget_tomorrow)
        else context.getString(com.example.R.string.widget_in_days, days)
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
