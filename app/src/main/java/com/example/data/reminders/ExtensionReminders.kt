package com.example.data.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.extensions.ExtensionPermission
import com.example.data.extensions.OrthoExtension
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TimeZone

/**
 * `OrthoTips.notifications` (extension guide, section 5): reminders scheduled by
 * extensions and shown by the app, even when Ortho Tips is closed.
 *
 * - [ExtensionReminderStore] is the truth; alarms are only triggers.
 * - Alarms use [AlarmManager.setAndAllowWhileIdle]: they fire in Doze and need
 *   no special permission, within a few minutes. (Exact alarms would need the
 *   restricted SCHEDULE_EXACT_ALARM; WorkManager can be deferred by hours.)
 * - One PendingIntent per reminder, keyed by `orthotips-notif://<extension>/<id>`,
 *   so replacing and cancelling always hit the right alarm.
 */
class ExtensionReminders(
    context: Context,
    /** The installed extension, or null; used when an alarm fires. */
    private val findExtension: suspend (String) -> OrthoExtension?
) {
    private val app = context.applicationContext
    val store = ExtensionReminderStore(File(app.filesDir, "extension_reminders.json"))

    // ---- what the bridge calls ----------------------------------------------------

    /** `{ available: true, granted }`: granted = the user lets the app notify and the channel is not blocked. */
    fun status(): JSONObject = JSONObject().put("available", true).put("granted", notificationsEnabled())
        .put("exact", canScheduleExact())

    /** Exact alarms: always below Android 12; from 12 on, only once the user allows « Alarmes et rappels ». */
    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 ||
        app.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    fun notificationsEnabled(): Boolean {
        val manager = NotificationManagerCompat.from(app)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = app.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    /** Creates or replaces a reminder; returns `{ id, at }`. */
    fun schedule(extensionId: String, args: JSONObject): JSONObject {
        val request = ReminderRules.validate(
            id = args.optStringOrNull("id"),
            title = args.optStringOrNull("title"),
            body = args.optStringOrNull("body"),
            at = args.optNumberOrNull("at"),
            repeatDays = args.optNumberOrNull("repeatDays"),
            data = args.optStringOrNull("data")
        )
        val previous = store.get(extensionId, request.id)
        val row = StoredReminder(
            extensionId = extensionId,
            id = request.id,
            title = request.title,
            body = request.body,
            nextAt = request.at,
            repeatDays = request.repeatDays,
            data = request.data,
            createdAt = previous?.createdAt ?: System.currentTimeMillis(),
            anchorAt = request.at,
            exact = args.optBoolean("exact", false)
        )
        store.put(row)   // throws "Too many notifications (max 64)" before anything is armed
        arm(row)
        return JSONObject().put("id", row.id).put("at", row.nextAt)
    }

    fun cancel(extensionId: String, id: String): Boolean {
        disarm(extensionId, id)
        return store.remove(extensionId, id)
    }

    fun cancelAll(extensionId: String): Int {
        val gone = store.removeAll(extensionId)
        gone.forEach { disarm(it.extensionId, it.id) }
        return gone.size
    }

    /** Sorted by next fire time; `at` is the stored value, exact. */
    fun list(extensionId: String): JSONArray = JSONArray(store.forExtension(extensionId).map { it.toPublicJson() })

    // ---- alarms ---------------------------------------------------------------------

    /**
     * An alarm fired. Posts the notification when the extension still exists and
     * still holds `notifications`, then re-arms a repeating reminder at its next
     * time (after catching up) or deletes a one-shot one.
     */
    suspend fun onAlarm(extensionId: String, id: String) {
        val row = store.get(extensionId, id) ?: return
        val extension = findExtension(extensionId)
        if (extension == null || !extension.has(ExtensionPermission.NOTIFICATIONS)) {
            store.remove(extensionId, id)
            return
        }
        if (notificationsEnabled()) post(row, extension)
        if (row.repeatDays > 0) {
            val next = ReminderRules.nextAfter(row.anchorAt, row.repeatDays, maxOf(System.currentTimeMillis(), row.nextAt), TimeZone.getDefault())
            val updated = row.copy(nextAt = next)
            store.put(updated, checkLimit = false)
            arm(updated)
        } else {
            store.remove(extensionId, id)
        }
    }

    /**
     * After a reboot, an app update or a clock change: alarms are gone or wrong,
     * so every stored reminder is armed again. On a time-zone change repeating
     * reminders keep their local wall-clock time in the new zone.
     */
    fun rearmAll(zoneChanged: Boolean) {
        val now = System.currentTimeMillis()
        for (row in store.all()) {
            try {
                var current = row
                if (zoneChanged && row.repeatDays > 0 && row.nextAt > now) {
                    val next = ReminderRules.nextAfter(row.anchorAt, row.repeatDays, now, TimeZone.getDefault())
                    if (next != row.nextAt) { current = row.copy(nextAt = next); store.put(current, checkLimit = false) }
                }
                arm(current)   // a time in the past fires straight away
            } catch (e: Exception) {
                Log.e(TAG, "Could not re-arm ${row.extensionId}/${row.id}", e)
            }
        }
    }

    private fun arm(row: StoredReminder) {
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        val intent = alarmIntent(row.extensionId, row.id)
        if (row.exact && canScheduleExact()) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, row.nextAt, intent)
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "Exact alarm refused, using an inexact one", e)
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, row.nextAt, intent)
    }

    private fun disarm(extensionId: String, id: String) {
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        manager.cancel(alarmIntent(extensionId, id))
    }

    private fun alarmIntent(extensionId: String, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            app, 0,
            Intent(app, ReminderAlarmReceiver::class.java).setData(uriOf(extensionId, id)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    // ---- the notification ----------------------------------------------------------------

    private fun post(row: StoredReminder, extension: OrthoExtension) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = app.getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(CHANNEL) == null) {
                manager?.createNotificationChannel(
                    NotificationChannel(CHANNEL, app.getString(R.string.reminders_channel), NotificationManager.IMPORTANCE_DEFAULT)
                        .apply { description = app.getString(R.string.reminders_channel_description) }
                )
            }
        }
        val open = Intent(app, MainActivity::class.java)
            .setData(uriOf(row.extensionId, row.id))
            .putExtra(EXTRA_EXTENSION, row.extensionId)
            .putExtra(EXTRA_ID, row.id)
            .putExtra(EXTRA_DATA, row.data)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val content = PendingIntent.getActivity(app, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val language = app.resources.configuration.locales[0]?.language ?: "fr"
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(row.title)   // plain text: never interpreted as HTML
            .setContentText(row.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(row.body))
            .setSubText(extension.manifest.localizedName(language))
            .setGroup("ext:${row.extensionId}")
            .setAutoCancel(true)
            .setContentIntent(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(app).notify(ReminderRules.notificationId(row.extensionId, row.id), notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission revoked", e)
        }
    }

    companion object {
        private const val TAG = "ExtensionReminders"
        const val CHANNEL = "extension_reminders"
        const val SCHEME = "orthotips-notif"
        const val EXTRA_EXTENSION = "com.example.REMINDER_EXTENSION"
        const val EXTRA_ID = "com.example.REMINDER_ID"
        const val EXTRA_DATA = "com.example.REMINDER_DATA"

        fun uriOf(extensionId: String, id: String): Uri =
            Uri.Builder().scheme(SCHEME).authority(extensionId).appendPath(id).build()
    }
}

private fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else opt(key)?.toString()

private fun JSONObject.optNumberOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else (opt(key) as? Number)?.toDouble() ?: opt(key)?.toString()?.toDoubleOrNull()
