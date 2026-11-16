package com.example.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.ContextCompat
import com.example.R
import java.util.Locale

/** Feeds the widget's list. Android calls this on a background thread. */
class OrthoWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        OrthoWidgetFactory(
            applicationContext,
            intent.getBooleanExtra(OrthoWidgetProvider.EXTRA_SHOW_SHIFTS, true),
            intent.getIntExtra(OrthoWidgetProvider.EXTRA_DAY_OFFSET, 0)
        )
}

private class OrthoWidgetFactory(
    private val baseContext: Context,
    private val showShifts: Boolean,
    private val dayOffset: Int
) : RemoteViewsService.RemoteViewsFactory {

    private var rows: List<WidgetRow> = emptyList()

    /**
     * Resolved on every refresh rather than once at construction: Android keeps a
     * factory alive across a language change, which used to leave the dates in the
     * old language while the labels switched.
     */
    private var context: Context = com.example.util.AppLocale.wrap(baseContext)

    /** Re-read with the data, so a theme switch recolours the rows too. */
    private var palette = WidgetTheme.palette(WidgetTheme.isDark(baseContext))

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        context = com.example.util.AppLocale.wrap(baseContext)
        palette = WidgetTheme.palette(WidgetTheme.isDark(baseContext))
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        rows = runCatching {
            if (showShifts) WidgetData.shifts(context, locale)
            else WidgetData.events(context, locale, dayOffset)
        }.getOrDefault(emptyList())
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val row = rows[position]
        return RemoteViews(context.packageName, R.layout.widget_row).apply {
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
            setViewVisibility(R.id.row_actions, if (showActions) android.view.View.VISIBLE else android.view.View.GONE)
            if (showActions) {
                setOnClickFillInIntent(R.id.row_attended, fillIn(row, "attended"))
                setOnClickFillInIntent(R.id.row_missed, fillIn(row, "missed"))
                setOnClickFillInIntent(R.id.row_note, fillIn(row, "note"))
            }
            setOnClickFillInIntent(R.id.row_container, fillIn(row, "open"))
        }
    }

    private fun fillIn(row: WidgetRow, mark: String): Intent = Intent().apply {
        putExtra(OrthoWidgetProvider.EXTRA_EVENT_ID, row.eventId)
        putExtra(OrthoWidgetProvider.EXTRA_PATIENT_ID, row.targetPatientId)
        putExtra("mark", mark)
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = true
}
