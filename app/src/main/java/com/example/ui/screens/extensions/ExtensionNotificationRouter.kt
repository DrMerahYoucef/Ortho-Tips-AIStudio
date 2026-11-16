package com.example.ui.screens.extensions

import android.content.Intent
import com.example.data.reminders.ExtensionReminders
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONObject

/**
 * Routes a tapped extension notification: to the extension if it is open
 * (`OrthoTips.notifications.onOpen`), otherwise by opening it with
 * `OrthoTips.notifications.launch` set for that opening only.
 */
object ExtensionNotificationRouter {

    data class Tap(val extensionId: String, val id: String, val data: String?)

    private val taps = Channel<Tap>(Channel.BUFFERED)

    /** Taps waiting to be handled by the navigation host, each delivered once. */
    val pending: Flow<Tap> = taps.receiveAsFlow()

    private val open = HashMap<String, (Tap) -> Unit>()
    private val launches = HashMap<String, Tap>()

    /** From MainActivity (onCreate or onNewIntent); consumes the extras so it is handled once. */
    fun post(intent: Intent?) {
        val extensionId = intent?.getStringExtra(ExtensionReminders.EXTRA_EXTENSION) ?: return
        val tap = Tap(extensionId, intent.getStringExtra(ExtensionReminders.EXTRA_ID).orEmpty(), intent.getStringExtra(ExtensionReminders.EXTRA_DATA))
        intent.removeExtra(ExtensionReminders.EXTRA_EXTENSION)
        taps.trySend(tap)
    }

    /** True when the extension is open and received the tap. */
    @Synchronized
    fun deliverToOpen(tap: Tap): Boolean {
        val handler = open[tap.extensionId] ?: return false
        handler(tap)
        return true
    }

    /** Remembered for the next opening of that extension (read by its `info()`). */
    @Synchronized
    fun setLaunch(tap: Tap) { launches[tap.extensionId] = tap }

    /** `{ source: "notification", id, data }` once, then null. */
    @Synchronized
    fun takeLaunch(extensionId: String): JSONObject? = launches.remove(extensionId)?.let {
        JSONObject().put("source", "notification").put("id", it.id).put("data", it.data ?: JSONObject.NULL)
    }

    @Synchronized
    fun register(extensionId: String, handler: (Tap) -> Unit) { open[extensionId] = handler }

    @Synchronized
    fun unregister(extensionId: String, handler: (Tap) -> Unit) { if (open[extensionId] === handler) open.remove(extensionId) }
}
