package com.example.data.reminders

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One scheduled reminder. [anchorAt] is the first time asked for; repeats are computed from it. */
data class StoredReminder(
    val extensionId: String,
    val id: String,
    val title: String,
    val body: String,
    val nextAt: Long,
    val repeatDays: Int,
    val data: String?,
    val createdAt: Long,
    val anchorAt: Long,
    /** Asked as exact (bridge v5): an exact alarm when Android allows it. */
    val exact: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("extensionId", extensionId).put("id", id).put("title", title).put("body", body)
        .put("nextAt", nextAt).put("repeatDays", repeatDays).put("data", data ?: JSONObject.NULL)
        .put("createdAt", createdAt).put("anchorAt", anchorAt).put("exact", exact)

    /** What `OrthoTips.notifications.list()` returns for it. */
    fun toPublicJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("body", body).put("at", nextAt)
        .put("repeatDays", repeatDays).put("data", data ?: JSONObject.NULL).put("exact", exact)

    companion object {
        fun fromJson(o: JSONObject) = StoredReminder(
            extensionId = o.getString("extensionId"),
            id = o.getString("id"),
            title = o.optString("title"),
            body = o.optString("body"),
            nextAt = o.getLong("nextAt"),
            repeatDays = o.optInt("repeatDays", 0),
            data = if (o.isNull("data")) null else o.optString("data"),
            createdAt = o.optLong("createdAt", 0L),
            anchorAt = o.optLong("anchorAt", o.getLong("nextAt")),
            exact = o.optBoolean("exact", false)
        )
    }
}

/**
 * Reminders of all extensions, keyed by (extensionId, id), in one JSON file
 * written atomically. The alarms are only triggers: this file is the truth,
 * `list()` reads it and every alarm is re-created from it after a reboot.
 * Plain Java + org.json, so it is unit-tested on the JVM.
 */
class ExtensionReminderStore(private val file: File) {

    fun all(): List<StoredReminder> = synchronized(LOCK) { read() }

    fun forExtension(extensionId: String): List<StoredReminder> =
        all().filter { it.extensionId == extensionId }.sortedWith(compareBy({ it.nextAt }, { it.id }))

    fun get(extensionId: String, id: String): StoredReminder? =
        all().firstOrNull { it.extensionId == extensionId && it.id == id }

    /**
     * Inserts or replaces (same extension and id). The 64 limit is checked
     * inside the same lock, so two quick calls can never exceed it.
     */
    fun put(reminder: StoredReminder, checkLimit: Boolean = true) = synchronized(LOCK) {
        val rows = read().toMutableList()
        if (checkLimit) ReminderRules.checkLimit(rows.filter { it.extensionId == reminder.extensionId }.map { it.id }, reminder.id)
        rows.removeAll { it.extensionId == reminder.extensionId && it.id == reminder.id }
        rows += reminder
        write(rows)
    }

    /** True if it existed. */
    fun remove(extensionId: String, id: String): Boolean = synchronized(LOCK) {
        val rows = read().toMutableList()
        val removed = rows.removeAll { it.extensionId == extensionId && it.id == id }
        if (removed) write(rows)
        removed
    }

    /** The removed rows, so their alarms can be cancelled. */
    fun removeAll(extensionId: String): List<StoredReminder> = synchronized(LOCK) {
        val rows = read()
        val (gone, kept) = rows.partition { it.extensionId == extensionId }
        if (gone.isNotEmpty()) write(kept)
        gone
    }

    private fun read(): List<StoredReminder> {
        if (!file.isFile) return emptyList()
        return try {
            val array = JSONObject(file.readText(Charsets.UTF_8)).optJSONArray("reminders") ?: JSONArray()
            (0 until array.length()).mapNotNull { i -> runCatching { StoredReminder.fromJson(array.getJSONObject(i)) }.getOrNull() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(rows: List<StoredReminder>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put("version", 1).put("reminders", JSONArray(rows.map { it.toJson() })).toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("Could not save reminders")
        }
    }

    private companion object {
        /** One lock for every store instance: receivers and the app may run at once. */
        val LOCK = Any()
    }
}
