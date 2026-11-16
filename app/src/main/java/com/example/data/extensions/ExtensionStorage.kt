package com.example.data.extensions

import android.content.Context
import android.content.SharedPreferences

/**
 * What one extension saves: its progress, settings, anything. Backs
 * `window.storage`, `localStorage` and `OrthoTips.storage` inside the page.
 *
 * One SharedPreferences file per extension, so deleting an extension (or
 * "Clear data") removes exactly its data and nothing else.
 */
class ExtensionStorage(context: Context, extensionId: String) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(fileName(extensionId), Context.MODE_PRIVATE)

    fun get(key: String): String? = prefs.getString(key, null)

    /** False when the key or value is refused for its size. */
    fun set(key: String, value: String): Boolean {
        if (key.isEmpty() || key.length > MAX_KEY_CHARS || value.length > MAX_VALUE_CHARS) return false
        prefs.edit().putString(key, value).apply()
        return true
    }

    fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun keys(prefix: String): List<String> =
        prefs.all.keys.filter { it.startsWith(prefix) }.sorted()

    companion object {
        const val MAX_KEY_CHARS = 200
        const val MAX_VALUE_CHARS = 5 * 1024 * 1024

        private fun fileName(extensionId: String) = "ext_data_$extensionId"

        fun clear(context: Context, extensionId: String) {
            val name = fileName(extensionId)
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            context.deleteSharedPreferences(name)
        }
    }
}
