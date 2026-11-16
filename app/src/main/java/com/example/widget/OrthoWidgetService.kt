package com.example.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.R
import com.example.util.AppLocale
import java.util.Locale

/** Feeds the widget's list on Android < 12. Android calls this on a background thread. */
class OrthoWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        OrthoWidgetFactory(
            applicationContext,
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
        )
}

private class OrthoWidgetFactory(
    private val baseContext: Context,
    private val appWidgetId: Int
) : RemoteViewsService.RemoteViewsFactory {

    @Volatile
    private var rows: List<WidgetRow> = emptyList()

    /**
     * Resolved on every refresh rather than once at construction: Android keeps a
     * factory alive across a language change, which used to leave the dates in the
     * old language while the labels switched.
     */
    @Volatile
    private var context: Context = AppLocale.wrap(baseContext)

    /** Re-read with the data, so a theme switch recolours the rows too. */
    @Volatile
    private var palette = WidgetTheme.palette(WidgetTheme.isDark(baseContext))

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        val wrapped = AppLocale.wrap(baseContext)
        context = wrapped
        palette = WidgetTheme.palette(WidgetTheme.isDark(wrapped))
        val locale = wrapped.resources.configuration.locales[0] ?: Locale.getDefault()
        val showShifts = if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) true
        else OrthoWidgetProvider.isShowingShifts(wrapped, appWidgetId)
        val dayOffset = if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) 0
        else OrthoWidgetProvider.dayOffset(wrapped, appWidgetId)

        rows = runCatching {
            if (showShifts) WidgetData.shifts(wrapped, locale)
            else WidgetData.events(wrapped, locale, dayOffset)
        }.getOrDefault(emptyList())
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val row = rows.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.widget_loading_placeholder)
        return OrthoWidgetProvider.buildRowRemoteViews(context, palette, row)
    }

    override fun getLoadingView(): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_loading_placeholder)

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long {
        val row = rows.getOrNull(position) ?: return position.toLong()
        return (row.eventId ?: "${row.title}:$position").hashCode().toLong()
    }

    override fun hasStableIds(): Boolean = true
}
