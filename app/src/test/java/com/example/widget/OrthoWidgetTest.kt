package com.example.widget

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.data.local.OrthoDatabase
import com.example.data.local.entity.PatientEntity
import com.example.data.local.entity.TimelineEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OrthoWidgetTest {

    @Test
    fun widgetLayoutsInflateAndApplyThroughRemoteViews() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parent = FrameLayout(context)

        val rootView = RemoteViews(context.packageName, R.layout.widget_ortho).apply(context, parent)
        assertNotNull(rootView.findViewById(R.id.widget_list))
        assertNotNull(rootView.findViewById(R.id.widget_empty))

        val placeholderView = RemoteViews(
            context.packageName,
            R.layout.widget_loading_placeholder
        ).apply(context, parent)
        assertNotNull(placeholderView)

        val todayRow = WidgetRow(
            title = "Jane Doe — Post-op review",
            subtitle = "10:30 · Check incision",
            accent = WidgetRow.Accent.NEUTRAL,
            targetPatientId = "pat_test",
            eventId = "evt_test",
            actionable = true
        )
        val tomorrowRow = WidgetRow(
            title = "Tuesday 17 November",
            subtitle = "Tomorrow",
            accent = WidgetRow.Accent.NEXT,
            actionable = false
        )

        listOf(true, false).forEach { dark ->
            val palette = WidgetTheme.palette(dark)

            val appliedToday = OrthoWidgetProvider
                .buildRowRemoteViews(context, palette, todayRow)
                .apply(context, parent)
            assertEquals(
                "Jane Doe — Post-op review",
                appliedToday.findViewById<TextView>(R.id.row_title).text.toString()
            )
            assertEquals(
                View.VISIBLE,
                appliedToday.findViewById<View>(R.id.row_actions).visibility
            )
            assertNotNull(appliedToday.findViewById<View>(R.id.row_accent))

            val appliedTomorrow = OrthoWidgetProvider
                .buildRowRemoteViews(context, palette, tomorrowRow)
                .apply(context, parent)
            assertEquals(
                View.GONE,
                appliedTomorrow.findViewById<View>(R.id.row_actions).visibility
            )
        }
    }

    @Test
    fun loadEventsReadsTodayAndTomorrowFromLocalDatabase() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = OrthoDatabase.getInstance(context)
        val patientId = "widget_test_patient"
        val todayEventId = "widget_test_today"
        val tomorrowEventId = "widget_test_tomorrow"

        val noonToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val noonTomorrow = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        try {
            db.patientDao().insertPatient(
                PatientEntity(
                    id = patientId,
                    patientCode = "WT-001",
                    fullName = "Test Patient"
                )
            )
            db.timelineEventDao().insertEvents(
                listOf(
                    TimelineEventEntity(
                        id = todayEventId,
                        patientId = patientId,
                        eventDate = noonToday,
                        title = "Cast check"
                    ),
                    TimelineEventEntity(
                        id = tomorrowEventId,
                        patientId = patientId,
                        eventDate = noonTomorrow,
                        title = "X-ray follow-up"
                    )
                )
            )

            val todayRows = WidgetData.loadEvents(context, Locale.ENGLISH, dayOffset = 0)
            val tomorrowRows = WidgetData.loadEvents(context, Locale.ENGLISH, dayOffset = 1)

            assertEquals(1, todayRows.count { it.eventId == todayEventId })
            assertEquals(true, todayRows.first { it.eventId == todayEventId }.actionable)
            assertEquals(1, tomorrowRows.count { it.eventId == tomorrowEventId })
            assertEquals(false, tomorrowRows.first { it.eventId == tomorrowEventId }.actionable)
        } finally {
            db.timelineEventDao().deleteEvent(todayEventId)
            db.timelineEventDao().deleteEvent(tomorrowEventId)
            db.patientDao().deletePatient(patientId)
        }
    }
}
