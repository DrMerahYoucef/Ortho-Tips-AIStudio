package com.example.widget

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import com.example.R
import com.example.data.prefs.ThemeMode

/**
 * Light / dark look for the home-screen widget.
 *
 * The widget is drawn by the launcher, not by the app, so it never sees the
 * app's Compose theme, and it cannot wait on the DataStore the rest of the
 * settings live in. The app therefore hands the theme choice over here, into a
 * small SharedPreferences store the widget can read synchronously (the same
 * reason [com.example.util.AppLocale] keeps its own store).
 *
 * Colours are applied from code rather than from night-qualified resources:
 * the launcher resolves resources with the *phone's* night mode, which would
 * ignore an in-app "Light" or "Dark" choice.
 */
object WidgetTheme {

    private const val PREFS = "ortho_widget_theme"
    private const val KEY_MODE = "theme_mode"
    private const val KEY_RENDERED_DARK = "rendered_dark"

    class Palette(
        @param:DrawableRes val background: Int,
        @param:DrawableRes val row: Int,
        @param:DrawableRes val tabSelected: Int,
        @param:DrawableRes val tabUnselected: Int,
        @param:ColorInt val tabSelectedText: Int,
        @param:ColorInt val tabDimmedText: Int,
        @param:ColorInt val arrow: Int,
        @param:ColorInt val dayLabel: Int,
        @param:ColorInt val title: Int,
        @param:ColorInt val subtitle: Int,
        @param:ColorInt val empty: Int
    )

    private val DARK = Palette(
        background = R.drawable.widget_background,
        row = R.drawable.widget_row_background,
        tabSelected = R.drawable.widget_tab_selected,
        tabUnselected = R.drawable.widget_tab_unselected,
        tabSelectedText = 0xFFFFFFFF.toInt(),
        tabDimmedText = 0x99FFFFFF.toInt(),
        arrow = 0xFFFFFFFF.toInt(),
        dayLabel = 0xFFD0D8E0.toInt(),
        title = 0xFFFFFFFF.toInt(),
        subtitle = 0xFFB0BEC5.toInt(),
        empty = 0xFFB0BEC5.toInt()
    )

    private val LIGHT = Palette(
        background = R.drawable.widget_background_light,
        row = R.drawable.widget_row_background_light,
        tabSelected = R.drawable.widget_tab_selected,
        tabUnselected = R.drawable.widget_tab_unselected_light,
        tabSelectedText = 0xFFFFFFFF.toInt(),
        tabDimmedText = 0x991B1D22.toInt(),
        arrow = 0xFF1B1D22.toInt(),
        dayLabel = 0xFF37424D.toInt(),
        title = 0xFF1B1D22.toInt(),
        subtitle = 0xFF5F6B76.toInt(),
        empty = 0xFF5F6B76.toInt()
    )

    fun palette(dark: Boolean): Palette = if (dark) DARK else LIGHT

    /** Whether the widget should be dark right now, following the app's own setting. */
    fun isDark(context: Context): Boolean = when (mode(context)) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        // The phone's setting, not a wrapped context's snapshot of it.
        ThemeMode.SYSTEM -> (Resources.getSystem().configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** Receives the app's theme choice; redraws the widget if its look changes. */
    fun setMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
        refreshIfStale(context)
    }

    /**
     * Redraws only when the widget was last drawn in the other look — e.g. the
     * phone switched to night mode while the app is set to follow the system.
     */
    fun refreshIfStale(context: Context) {
        val prefs = prefs(context)
        if (prefs.contains(KEY_RENDERED_DARK) &&
            prefs.getBoolean(KEY_RENDERED_DARK, true) == isDark(context)
        ) return
        OrthoWidgetProvider.refreshAll(context)
    }

    internal fun markRendered(context: Context, dark: Boolean) {
        prefs(context).edit().putBoolean(KEY_RENDERED_DARK, dark).apply()
    }

    private fun mode(context: Context): ThemeMode =
        runCatching {
            ThemeMode.valueOf(prefs(context).getString(KEY_MODE, null) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
