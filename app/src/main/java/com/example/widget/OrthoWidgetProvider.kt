package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.OrthoDatabase
import com.example.data.local.entity.Attendance
import com.example.util.AppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

/**
 * Home-screen widget with two tabs: the coming on-call days, and the day's
 * appointments. On the appointments tab, arrows move between today and tomorrow,
 * and today's rows carry three buttons: came, missed, note.
 *
 * On Android 12+ (API 31+), list rows are pushed directly via
 * [RemoteViews.RemoteCollectionItems] so appointments render immediately without
 * waiting on asynchronous [android.widget.RemoteViewsService] binding or showing
 * the system "Loading..." placeholder.
 */
class OrthoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val appContext = context.applicationContext
        val pendingResult = runCatching { goAsync() }.getOrNull()
        widgetScope.launch {
            try {
                updateWidgets(appContext, manager, ids)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == ACTION_SELECT_TAB || action == ACTION_SELECT_DAY ||
            action == ACTION_MARK || action == ACTION_REFRESH
        ) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val id = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
            val pendingResult = runCatching { goAsync() }.getOrNull()
            widgetScope.launch {
                try {
                    when (action) {
                        ACTION_SELECT_TAB -> {
                            if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return@launch
                            prefs(appContext).edit()
                                .putBoolean(keyShifts(id), intent.getBooleanExtra(EXTRA_SHOW_SHIFTS, true))
                                .apply()
                            updateWidgets(appContext, manager, intArrayOf(id))
                        }
                        ACTION_SELECT_DAY -> {
                            if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return@launch
                            prefs(appContext).edit()
                                .putInt(keyDay(id), intent.getIntExtra(EXTRA_DAY_OFFSET, 0).coerceIn(0, 1))
                                .apply()
                            updateWidgets(appContext, manager, intArrayOf(id))
                        }
                        ACTION_MARK -> {
                            val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return@launch
                            markIfDue(appContext, eventId, intent.getBooleanExtra(EXTRA_ATTENDED, true))
                        }
                        ACTION_REFRESH -> {
                            val ids = manager.getAppWidgetIds(
                                ComponentName(appContext, OrthoWidgetProvider::class.java)
                            )
                            if (ids.isNotEmpty()) updateWidgets(appContext, manager, ids)
                        }
                    }
                } finally {
                    pendingResult?.finish()
                }
            }
            return
        }
        super.onReceive(context, intent)
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

        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun keyShifts(id: Int) = "shifts_$id"
        private fun keyDay(id: Int) = "day_$id"

        internal fun isShowingShifts(context: Context, id: Int): Boolean =
            prefs(context).getBoolean(keyShifts(id), true)

        internal fun dayOffset(context: Context, id: Int): Int =
            prefs(context).getInt(keyDay(id), 0).coerceIn(0, 1)

        /**
         * Renders all requested widgets off the main thread.
         */
        private suspend fun updateWidgets(
            rawContext: Context,
            manager: AppWidgetManager,
            ids: IntArray
        ) {
            ids.forEach { id -> render(rawContext, manager, id) }
        }

        private suspend fun render(rawContext: Context, manager: AppWidgetManager, id: Int) {
            // The widget lives outside the app's activities, so it has to ask for the
            // in-app language itself; otherwise it keeps speaking the phone's language.
            val context = AppLocale.wrap(rawContext)
            val showingShifts = isShowingShifts(context, id)
            val dayOffset = dayOffset(context, id)
            val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()

            // Light or dark, following the app's theme setting rather than the launcher's.
            val dark = WidgetTheme.isDark(context)
            val palette = WidgetTheme.palette(dark)

            val emptyRes = when {
                showingShifts -> R.string.widget_no_shifts
                dayOffset == 0 -> R.string.widget_no_events_today
                else -> R.string.widget_no_events
            }

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
                setTextViewText(R.id.widget_tab_shifts, context.getString(R.string.widget_shifts_title))
                setTextViewText(R.id.widget_tab_events, context.getString(R.string.widget_events_tab))

                // The day bar only makes sense on the appointments tab.
                setViewVisibility(R.id.widget_daybar, if (showingShifts) View.GONE else View.VISIBLE)
                setTextViewText(R.id.widget_day_label, WidgetData.dayLabel(context, locale, dayOffset))
                setOnClickPendingIntent(R.id.widget_prev_day, dayIntent(context, id, 0))
                setOnClickPendingIntent(R.id.widget_next_day, dayIntent(context, id, 1))

                setEmptyView(R.id.widget_list, R.id.widget_empty)
                setTextViewText(R.id.widget_empty, context.getString(emptyRes))

                setOnClickPendingIntent(R.id.widget_tab_shifts, tabIntent(context, id, true))
                setOnClickPendingIntent(R.id.widget_tab_events, tabIntent(context, id, false))

                // One template for the whole list; each row fills in what it needs.
                setPendingIntentTemplate(R.id.widget_list, rowTemplate(context, id))

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val rows = runCatching {
                        if (showingShifts) WidgetData.loadShifts(context, locale)
                        else WidgetData.loadEvents(context, locale, dayOffset)
                    }.getOrDefault(emptyList())

                    val builder = RemoteViews.RemoteCollectionItems.Builder()
                        .setHasStableIds(true)
                        .setViewTypeCount(1)
                    rows.forEachIndexed { index, row ->
                        val itemId = (row.eventId ?: "${row.title}:$index").hashCode().toLong()
                        builder.addItem(itemId, buildRowRemoteViews(context, palette, row))
                    }
                    setRemoteAdapter(R.id.widget_list, builder.build())
                } else {
                    // Stable URI per widget ID so switching tabs/days updates data in-place
                    // without tearing down and re-binding the RemoteViewsService.
                    setRemoteAdapter(R.id.widget_list, Intent(context, OrthoWidgetService::class.java).apply {
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        data = Uri.parse("ortho://widget/$id")
                    })
                }
            }

            manager.updateAppWidget(id, views)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
            }
            WidgetTheme.markRendered(context, dark)
        }

        internal fun buildRowRemoteViews(
            context: Context,
            palette: WidgetTheme.Palette,
            row: WidgetRow
        ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_row).apply {
            setTextViewText(R.id.row_title, row.title)
            setTextViewText(R.id.row_subtitle, row.subtitle)
            setInt(R.id.row_container, "setBackgroundResource", palette.row)
            setTextColor(R.id.row_title, palette.title)
            setTextColor(R.id.row_subtitle, palette.subtitle)
            val color = ContextCompat.getColor(
                context,
                when (row.accent) {
                    WidgetRow.Accent.CURRENT -> R.color.widget_current
                    WidgetRow.Accent.NEXT -> R.color.widget_next
                    WidgetRow.Accent.LATER -> R.color.widget_later
                    WidgetRow.Accent.ATTENDED -> R.color.widget_attended
                    WidgetRow.Accent.MISSED -> R.color.widget_missed
                    WidgetRow.Accent.NEUTRAL -> R.color.widget_neutral
                }
            )
            setInt(R.id.row_accent, "setBackgroundColor", color)

            // Came / missed / note, on today's appointments only.
            val showActions = row.actionable && row.eventId != null
            setViewVisibility(R.id.row_actions, if (showActions) View.VISIBLE else View.GONE)
            if (showActions) {
                setOnClickFillInIntent(R.id.row_attended, fillIn(row, "attended"))
                setOnClickFillInIntent(R.id.row_missed, fillIn(row, "missed"))
                setOnClickFillInIntent(R.id.row_note, fillIn(row, "note"))
            }
            setOnClickFillInIntent(R.id.row_container, fillIn(row, "open"))
        }

        private fun fillIn(row: WidgetRow, mark: String): Intent = Intent().apply {
            putExtra(EXTRA_EVENT_ID, row.eventId)
            putExtra(EXTRA_PATIENT_ID, row.targetPatientId)
            putExtra("mark", mark)
        }

        /**
         * Records came / missed, but only for an appointment whose day has come,
         * the same rule as the Agenda. A tap on a future appointment (a stale row,
         * or tomorrow's page) is ignored instead of saving a false mark.
         */
        internal suspend fun markIfDue(context: Context, eventId: String, attended: Boolean) {
            val dao = OrthoDatabase.getInstance(context).timelineEventDao()
            val event = dao.getEventDirect(eventId) ?: return
            if (event.eventDate >= startOfTomorrow()) return
            dao.setAttendance(
                eventId,
                if (attended) Attendance.ATTENDED.name else Attendance.MISSED.name
            )
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, OrthoWidgetProvider::class.java))
            if (ids.isNotEmpty()) updateWidgets(appContext, manager, ids)
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
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, OrthoWidgetProvider::class.java))
            if (ids.isEmpty()) return
            widgetScope.launch {
                updateWidgets(appContext, manager, ids)
            }
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
            eventId != null && (mark == "attended" || mark == "missed") -> {
                val appContext = context.applicationContext
                val pendingResult = runCatching { goAsync() }.getOrNull()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        OrthoWidgetProvider.markIfDue(appContext, eventId, attended = mark == "attended")
                    } finally {
                        pendingResult?.finish()
                    }
                }
            }
            else -> {
                // Notes need a keyboard, which a widget cannot offer: open the app
                // on that appointment instead.
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra(OrthoWidgetProvider.EXTRA_OPEN_EVENT, eventId)
                        putExtra(OrthoWidgetProvider.EXTRA_PATIENT_ID, patientId)
                    }
                )
            }
        }
    }
}
