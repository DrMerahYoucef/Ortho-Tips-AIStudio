package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.local.OrthoDatabase
import com.example.data.local.entity.Attendance
import com.example.util.AppLocale
import kotlinx.coroutines.runBlocking
import java.util.Calendar

/**
 * Home-screen widget with two tabs: the coming on-call days, and the day's
 * appointments. On the appointments tab, arrows move between today and tomorrow,
 * and today's rows carry three buttons: came, missed, note.
 */
class OrthoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> render(context, manager, id) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val manager = AppWidgetManager.getInstance(context)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        when (intent.action) {
            ACTION_SELECT_TAB -> {
                if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
                prefs(context).edit()
                    .putBoolean(keyShifts(id), intent.getBooleanExtra(EXTRA_SHOW_SHIFTS, true))
                    .apply()
                refresh(context, manager, id)
            }
            ACTION_SELECT_DAY -> {
                if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
                prefs(context).edit()
                    .putInt(keyDay(id), intent.getIntExtra(EXTRA_DAY_OFFSET, 0).coerceIn(0, 1))
                    .apply()
                refresh(context, manager, id)
            }
            ACTION_MARK -> {
                val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
                markIfDue(context, eventId, intent.getBooleanExtra(EXTRA_ATTENDED, true))
            }
            ACTION_REFRESH -> refreshAll(context)
        }
    }

    private fun refresh(context: Context, manager: AppWidgetManager, id: Int) {
        render(context, manager, id)
        manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
    }

    private fun render(rawContext: Context, manager: AppWidgetManager, id: Int) {
        // The widget lives outside the app's activities, so it has to ask for the
        // in-app language itself; otherwise it keeps speaking the phone's language.
        val context = AppLocale.wrap(rawContext)
        val showingShifts = prefs(context).getBoolean(keyShifts(id), true)
        val dayOffset = prefs(context).getInt(keyDay(id), 0)
        val locale = context.resources.configuration.locales[0]

        // Light or dark, following the app's theme setting rather than the launcher's.
        val dark = WidgetTheme.isDark(context)
        val palette = WidgetTheme.palette(dark)

        val views = RemoteViews(context.packageName, R.layout.widget_ortho).apply {
            setInt(R.id.widget_root, "setBackgroundResource", palette.background)
            setInt(
                R.id.widget_tab_shifts, "setBackgroundResource",
                if (showingShifts) palette.tabSelected else palette.tabUnselected
            )
            setInt(
                R.id.widget_tab_events, "setBackgroundResource",
                if (showingShifts) palette.tabUnselected else palette.tabSelected
            )
            setTextColor(R.id.widget_tab_shifts, if (showingShifts) palette.tabSelectedText else palette.tabDimmedText)
            setTextColor(R.id.widget_tab_events, if (showingShifts) palette.tabDimmedText else palette.tabSelectedText)
            setInt(R.id.widget_prev_day, "setBackgroundResource", palette.tabUnselected)
            setInt(R.id.widget_next_day, "setBackgroundResource", palette.tabUnselected)
            setTextColor(R.id.widget_prev_day, palette.arrow)
            setTextColor(R.id.widget_next_day, palette.arrow)
            setTextColor(R.id.widget_day_label, palette.dayLabel)
            setTextColor(R.id.widget_empty, palette.empty)
            setTextViewText(R.id.widget_tab_events, context.getString(R.string.widget_events_tab))

            // The day bar only makes sense on the appointments tab.
            setViewVisibility(R.id.widget_daybar, if (showingShifts) View.GONE else View.VISIBLE)
            setTextViewText(R.id.widget_day_label, WidgetData.dayLabel(context, locale, dayOffset))
            setOnClickPendingIntent(R.id.widget_prev_day, dayIntent(context, id, 0))
            setOnClickPendingIntent(R.id.widget_next_day, dayIntent(context, id, 1))

            setRemoteAdapter(R.id.widget_list, Intent(context, OrthoWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(EXTRA_SHOW_SHIFTS, showingShifts)
                putExtra(EXTRA_DAY_OFFSET, dayOffset)
                // A unique URI stops Android from reusing another page's adapter.
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            })
            setEmptyView(R.id.widget_list, R.id.widget_empty)
            setTextViewText(
                R.id.widget_empty,
                context.getString(if (showingShifts) R.string.widget_no_shifts else R.string.widget_no_events)
            )

            setOnClickPendingIntent(R.id.widget_tab_shifts, tabIntent(context, id, true))
            setOnClickPendingIntent(R.id.widget_tab_events, tabIntent(context, id, false))

            // One template for the whole list; each row fills in what it needs.
            setPendingIntentTemplate(R.id.widget_list, rowTemplate(context, id))
        }
        manager.updateAppWidget(id, views)
        manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
        WidgetTheme.markRendered(context, dark)
    }

    companion object {
        const val ACTION_SELECT_TAB = "com.example.widget.SELECT_TAB"
        const val ACTION_SELECT_DAY = "com.example.widget.SELECT_DAY"
        const val ACTION_MARK = "com.example.widget.MARK"
        const val ACTION_REFRESH = "com.example.widget.REFRESH"
        const val EXTRA_SHOW_SHIFTS = "show_shifts"
        const val EXTRA_DAY_OFFSET = "day_offset"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_PATIENT_ID = "patient_id"
        const val EXTRA_ATTENDED = "attended"
        const val EXTRA_OPEN_EVENT = "open_event"
        private const val PREFS = "ortho_widget"

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun keyShifts(id: Int) = "shifts_$id"
        private fun keyDay(id: Int) = "day_$id"

        /**
         * Records came / missed, but only for an appointment whose day has come,
         * the same rule as the Agenda. A tap on a future appointment (a stale row,
         * or tomorrow's page) is ignored instead of saving a false mark.
         */
        internal fun markIfDue(context: Context, eventId: String, attended: Boolean) {
            val dao = OrthoDatabase.getInstance(context).timelineEventDao()
            runBlocking {
                val event = dao.getEventDirect(eventId) ?: return@runBlocking
                if (event.eventDate >= startOfTomorrow()) return@runBlocking
                dao.setAttendance(
                    eventId,
                    if (attended) Attendance.ATTENDED.name else Attendance.MISSED.name
                )
            }
            refreshAll(context)
        }

        private fun startOfTomorrow(): Long = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        private fun tabIntent(context: Context, id: Int, shifts: Boolean): PendingIntent =
            broadcast(context, id * 10 + if (shifts) 1 else 2, Intent(context, OrthoWidgetProvider::class.java).apply {
                action = ACTION_SELECT_TAB
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(EXTRA_SHOW_SHIFTS, shifts)
            })

        private fun dayIntent(context: Context, id: Int, offset: Int): PendingIntent =
            broadcast(context, id * 10 + 3 + offset, Intent(context, OrthoWidgetProvider::class.java).apply {
                action = ACTION_SELECT_DAY
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(EXTRA_DAY_OFFSET, offset)
            })

        /**
         * Rows fill this in with either a mark action or the ids needed to open the
         * app, so one template covers the three buttons and the row itself.
         */
        private fun rowTemplate(context: Context, id: Int): PendingIntent =
            mutableBroadcast(context, id * 10 + 5, Intent(context, OrthoWidgetRouter::class.java).apply {
                action = ACTION_MARK
            })

        private fun broadcast(context: Context, requestCode: Int, intent: Intent): PendingIntent =
            PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        /** List templates must stay mutable: each row fills in its own extras. */
        private fun mutableBroadcast(context: Context, requestCode: Int, intent: Intent): PendingIntent =
            PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

        /** Called after shifts, appointments, the language or the theme change. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, OrthoWidgetProvider::class.java))
            if (ids.isEmpty()) return
            OrthoWidgetProvider().onUpdate(context, manager, ids)
        }
    }
}

/**
 * Receives what a row's buttons fill in. Marking happens here; the note button
 * and a tap on the row itself open the app on that appointment.
 */
class OrthoWidgetRouter : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra(OrthoWidgetProvider.EXTRA_EVENT_ID)
        val patientId = intent.getStringExtra(OrthoWidgetProvider.EXTRA_PATIENT_ID)
        val mark = intent.getStringExtra("mark")
        when {
            eventId != null && (mark == "attended" || mark == "missed") ->
                OrthoWidgetProvider.markIfDue(context, eventId, attended = mark == "attended")
            else -> {
                // Notes need a keyboard, which a widget cannot offer: open the app
                // on that appointment instead.
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(OrthoWidgetProvider.EXTRA_OPEN_EVENT, eventId)
                        putExtra(OrthoWidgetProvider.EXTRA_PATIENT_ID, patientId)
                    }
                )
            }
        }
    }
}
