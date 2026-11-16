package com.example.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import java.util.Locale

/**
 * Per-app language selection.
 *
 * Deliberately backed by [android.content.SharedPreferences] rather than the
 * DataStore the rest of the app uses. The locale has to be known inside
 * `attachBaseContext`, which runs before anything can suspend and before the
 * first frame — reading it asynchronously there means the first screen renders in
 * the wrong language and then flickers. This is the one setting that genuinely
 * needs a synchronous read, so it gets its own small store.
 *
 * On Android 13+ the choice is also handed to the platform's per-app language
 * setting, so the app appears in the system language list and the two cannot
 * disagree.
 */
object AppLocale {

    /** Language options offered in settings. */
    enum class Option(val tag: String) {
        /** Follow the device language, falling back to English. */
        SYSTEM(""),
        ENGLISH("en"),
        FRENCH("fr");

        companion object {
            fun fromTag(tag: String?): Option =
                entries.firstOrNull { it.tag == tag } ?: SYSTEM
        }
    }

    private const val PREFS_NAME = "ortho_locale"
    private const val KEY_LANGUAGE_TAG = "language_tag"

    fun currentOption(context: Context): Option =
        Option.fromTag(
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE_TAG, null)
        )

    /**
     * Records the choice. The caller is responsible for recreating the activity;
     * this deliberately does not do it, so a screen can save and animate out
     * before the recreate rather than being yanked mid-tap.
     */
    fun setOption(context: Context, option: Option) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE_TAG, option.tag)
            .apply()
        applyToSystem(context, option)
    }

    /**
     * Wraps [base] so every resource lookup resolves in the chosen language.
     *
     * Called from `attachBaseContext`. Returns [base] untouched for SYSTEM, which
     * leaves Android's own resolution — including regional variants and RTL — in
     * charge rather than second-guessing it.
     */
    fun wrap(base: Context): Context {
        val option = currentOption(base)
        if (option == Option.SYSTEM) return base

        val locale = Locale.forLanguageTag(option.tag)
        Locale.setDefault(locale)

        val configuration = Configuration(base.resources.configuration)
        val localeList = LocaleList(locale)
        LocaleList.setDefault(localeList)
        configuration.setLocales(localeList)
        // setLayoutDirection matters even for two LTR languages: without it a
        // configuration carried over from an RTL system locale keeps its
        // direction and mirrors the whole UI.
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }

    /**
     * Mirrors the choice into Android 13+'s per-app language setting, so the app
     * shows up under Settings → Apps → Ortho Tips → Language and the two never
     * drift apart. A no-op below 33.
     */
    private fun applyToSystem(context: Context, option: Option) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching { setSystemLocales(context, option) }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun setSystemLocales(context: Context, option: Option) {
        val manager = context.getSystemService(android.app.LocaleManager::class.java) ?: return
        manager.applicationLocales = if (option == Option.SYSTEM) {
            LocaleList.getEmptyLocaleList()
        } else {
            LocaleList.forLanguageTags(option.tag)
        }
    }

    /**
     * Display name for a language, written in that language.
     *
     * A French speaker hunting for their language scans for "Français", not for
     * "French" — so the list is never translated into the current UI language.
     */
    fun displayName(option: Option, context: Context): String = when (option) {
        Option.SYSTEM -> context.getString(com.example.R.string.language_system)
        Option.ENGLISH -> "English"
        Option.FRENCH -> "Français"
    }
}
