package com.example.data.sms

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

/**
 * Bank movements read from SMS, waiting for the extensions that hold the
 * `sms` permission (extension guide, section 5).
 *
 * - [BankSmsReceiver] adds movements as they arrive — only while at least one
 *   installed extension may read them ([setConsumers]); otherwise nothing is kept.
 * - Each extension reads what it has not acknowledged yet ([pending]) and
 *   acknowledges it ([ack]); entries carry a growing `seq`.
 * - [history] reads earlier movements from the phone's SMS inbox, once, when
 *   an extension sets up an account.
 *
 * Only what [BankSmsParser] accepts is ever stored or returned, so codes and
 * any other SMS never reach an extension.
 */
class BankSmsInbox(context: Context) {

    private val app = context.applicationContext
    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Extensions allowed to read bank SMS; kept up to date by the extension repository. */
    fun setConsumers(ids: Collection<String>) {
        prefs.edit().putStringSet(KEY_CONSUMERS, ids.toSet()).apply()
    }

    fun consumers(): Set<String> = prefs.getStringSet(KEY_CONSUMERS, emptySet()).orEmpty()

    fun smsGranted(): Boolean =
        granted(Manifest.permission.RECEIVE_SMS) && granted(Manifest.permission.READ_SMS)

    fun notificationsAllowed(): Boolean =
        NotificationManagerCompat.from(app).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))

    fun status(): JSONObject = JSONObject()
        .put("available", true)
        .put("granted", smsGranted())
        .put("notifications", notificationsAllowed())

    /** Stores a movement that just arrived; null when it is a duplicate. */
    @Synchronized
    fun add(sms: BankSms, sender: String, body: String, sentAt: Long): JSONObject? {
        val entries = entries()
        val print = fingerprint(sms, sentAt)
        for (i in 0 until entries.length()) {
            if (entries.optJSONObject(i)?.optString("fingerprint") == print) return null
        }
        val seq = prefs.getLong(KEY_NEXT_SEQ, 1L)
        val entry = toJson(sms, sender, body, sentAt, System.currentTimeMillis())
            .put("seq", seq)
            .put("id", "q:$seq")
            .put("fingerprint", print)
        entries.put(entry)
        val kept = JSONArray()
        for (i in maxOf(0, entries.length() - MAX_ENTRIES) until entries.length()) kept.put(entries.get(i))
        prefs.edit().putString(KEY_ENTRIES, kept.toString()).putLong(KEY_NEXT_SEQ, seq + 1).commit()
        arrivalsFlow.tryEmit(Unit)
        return entry
    }

    /** Movements [extensionId] has not acknowledged yet, oldest first. */
    fun pending(extensionId: String): JSONArray {
        val cursor = prefs.getLong(KEY_CURSOR + extensionId, 0L)
        val entries = entries()
        val out = JSONArray()
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            if (e.optLong("seq") > cursor) out.put(e)
        }
        return out
    }

    fun ack(extensionId: String, upTo: Long) {
        val current = prefs.getLong(KEY_CURSOR + extensionId, 0L)
        if (upTo > current) prefs.edit().putLong(KEY_CURSOR + extensionId, upTo).apply()
    }

    /**
     * Earlier movements from the SMS inbox, oldest first. Every message goes
     * through [BankSmsParser]; anything it does not accept (codes, personal
     * messages) is skipped and never leaves this method.
     */
    fun history(limit: Int = HISTORY_LIMIT): JSONArray {
        check(granted(Manifest.permission.READ_SMS)) { "SMS access not granted" }
        val found = ArrayList<JSONObject>()
        app.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT),
            null, null,
            Telephony.Sms.DEFAULT_SORT_ORDER // newest first
        )?.use { c ->
            var scanned = 0
            while (c.moveToNext() && scanned < limit) {
                scanned++
                val body = c.getString(2) ?: continue
                val sms = BankSmsParser.parse(body) ?: continue
                val received = c.getLong(3)
                val sent = c.getLong(4).takeIf { it > 0 } ?: received
                found += toJson(sms, c.getString(1).orEmpty(), body, sent, received)
                    .put("id", "h:" + c.getLong(0))
                    .put("fingerprint", fingerprint(sms, sent))
            }
        }
        val out = JSONArray()
        found.asReversed().forEach { out.put(it) }
        return out
    }

    /** "Dépense CCP : −30 000,00 DA · nouveau solde 93 637,47 DA", opening the consuming extension. */
    fun notify(entry: JSONObject) {
        if (!notificationsAllowed()) return
        val target = consumers().sorted().firstOrNull() ?: return
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, app.getString(R.string.sms_notif_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val amount = money(entry.optDouble("amount"))
        val debit = entry.optString("kind") == "debit"
        val title = app.getString(if (debit) R.string.sms_notif_debit else R.string.sms_notif_credit, amount)
        val text = if (entry.has("balance") && !entry.isNull("balance")) {
            app.getString(R.string.sms_notif_balance, money(entry.optDouble("balance")))
        } else {
            app.getString(R.string.sms_notif_open)
        }
        val open = Intent(app, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_EXTENSION, target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = PendingIntent.getActivity(
            app, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bank_sms)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(NOTIFICATION_BASE + entry.optLong("seq").toInt(), notification) }
    }

    // ---- internals -------------------------------------------------------------

    private fun entries(): JSONArray =
        runCatching { JSONArray(prefs.getString(KEY_ENTRIES, "[]")) }.getOrDefault(JSONArray())

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    private fun toJson(sms: BankSms, sender: String, body: String, sentAt: Long, receivedAt: Long) = JSONObject()
        .put("kind", if (sms.kind == BankSms.Kind.DEBIT) "debit" else "credit")
        .put("amount", sms.amount)
        .put("balance", sms.balance ?: JSONObject.NULL)
        .put("currency", sms.currency)
        .put("sender", sender)
        .put("text", body)
        .put("sentAt", sentAt)
        .put("receivedAt", receivedAt)

    private fun money(value: Double): String =
        NumberFormat.getNumberInstance(Locale.FRANCE).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(value)

    companion object {
        private const val PREFS = "bank_sms_inbox"
        private const val KEY_ENTRIES = "entries"
        private const val KEY_NEXT_SEQ = "next_seq"
        private const val KEY_CONSUMERS = "consumers"
        private const val KEY_CURSOR = "cursor."
        private const val MAX_ENTRIES = 500
        private const val HISTORY_LIMIT = 5000
        private const val CHANNEL = "bank_sms"
        private const val NOTIFICATION_BASE = 70_000

        /** Intent extra: id of the extension to open (from a notification). */
        const val EXTRA_OPEN_EXTENSION = "com.example.OPEN_EXTENSION"

        private val arrivalsFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

        /** Emits when a movement arrives while the app is running (the open extension refreshes). */
        val arrivals: SharedFlow<Unit> = arrivalsFlow.asSharedFlow()

        /**
         * Identifies one movement whether it comes from the live receiver or from
         * the inbox, so the history import and live messages never double-count.
         * The reported balance changes with every movement, making it unique.
         */
        fun fingerprint(sms: BankSms, sentAt: Long): String =
            if (sms.balance != null) "${sms.kind}|${sms.amount}|${sms.balance}"
            else "${sms.kind}|${sms.amount}|${sentAt / 60_000}"
    }
}
