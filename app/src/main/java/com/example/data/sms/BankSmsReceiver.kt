package com.example.data.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Receives incoming SMS and keeps bank movements for extensions holding the
 * `sms` permission. Does nothing at all when no such extension is installed,
 * and keeps nothing [BankSmsParser] does not recognise as a movement.
 */
class BankSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val inbox = BankSmsInbox(context)
        if (inbox.consumers().isEmpty()) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // A long SMS arrives in parts; put each sender's parts back together.
        messages.filterNotNull()
            .groupBy { it.displayOriginatingAddress ?: it.originatingAddress.orEmpty() }
            .forEach { (sender, parts) ->
                val body = parts.joinToString("") { it.displayMessageBody ?: it.messageBody.orEmpty() }
                val sms = BankSmsParser.parse(body) ?: return@forEach
                val entry = inbox.add(sms, sender, body, parts.first().timestampMillis) ?: return@forEach
                inbox.notify(entry)
            }
    }
}
