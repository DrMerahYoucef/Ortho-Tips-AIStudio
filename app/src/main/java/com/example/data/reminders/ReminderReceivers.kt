package com.example.data.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.OrthoApplication
import kotlinx.coroutines.runBlocking

private const val TAG = "ExtensionReminders"

private fun remindersOf(context: Context): ExtensionReminders? =
    (context.applicationContext as? OrthoApplication)?.appModule?.extensionReminders

/** A reminder's alarm fired. Never crashes the app: errors are logged. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val extensionId = uri.authority ?: return
        val id = uri.lastPathSegment ?: return
        val pending = goAsync()
        Thread {
            try {
                runBlocking { remindersOf(context)?.onAlarm(extensionId, id) }
            } catch (t: Throwable) {
                Log.e(TAG, "Reminder $extensionId/$id failed", t)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** Boot, app update, clock or time-zone change: every stored reminder is armed again. */
class ReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val zoneChanged = intent.action == Intent.ACTION_TIMEZONE_CHANGED || intent.action == Intent.ACTION_TIME_CHANGED
        val pending = goAsync()
        Thread {
            try {
                remindersOf(context)?.rearmAll(zoneChanged)
            } catch (t: Throwable) {
                Log.e(TAG, "Could not restore reminders after ${intent.action}", t)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
