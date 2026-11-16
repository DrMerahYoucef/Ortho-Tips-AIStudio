package com.example.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri

/**
 * Turns a phone number or email address into the things a surgeon actually wants
 * to do with it.
 *
 * Two constraints shape this. Android 11 hides other installed apps unless they
 * are declared in `<queries>`, so the manifest lists the messaging apps by
 * package and by intent; without that, every availability check silently returns
 * "not installed". And dialling uses `ACTION_DIAL` rather than `ACTION_CALL`,
 * which places the number in the dialer without needing the `CALL_PHONE`
 * permission — a permission this app has no business holding.
 */
object ContactActions {

    /** A messaging app that can open a chat directly from a phone number. */
    data class MessagingApp(
        val id: String,
        val label: String,
        val packageNames: List<String>,
        /** Builds the deep link for a number already stripped to digits. */
        val linkFor: (String) -> Uri
    )

    private val messagingApps = listOf(
        MessagingApp(
            id = "whatsapp",
            label = "WhatsApp",
            packageNames = listOf("com.whatsapp", "com.whatsapp.w4b"),
            linkFor = { digits -> "https://wa.me/$digits".toUri() }
        ),
        MessagingApp(
            id = "telegram",
            label = "Telegram",
            packageNames = listOf("org.telegram.messenger", "org.telegram.messenger.web"),
            // tg://resolve is the only form that opens a chat by number; the
            // https://t.me/ equivalent is an invite link, not a lookup.
            linkFor = { digits -> "tg://resolve?phone=$digits".toUri() }
        ),
        MessagingApp(
            id = "viber",
            label = "Viber",
            packageNames = listOf("com.viber.voip"),
            linkFor = { digits -> "viber://chat?number=%2B$digits".toUri() }
        )
    )

    /**
     * Digits only, with any leading plus dropped.
     *
     * Deep links reject the spaces and dashes a phone number is usually written
     * with, so "+213 555 01 02 03" has to become "213555010203" before it goes
     * into a URI.
     */
    fun normalizeNumber(raw: String): String =
        raw.filter { it.isDigit() }

    /** Messaging apps present on this device that can take a phone number. */
    fun availableMessagingApps(context: Context): List<MessagingApp> {
        val packageManager = context.packageManager
        return messagingApps.filter { app ->
            app.packageNames.any { packageManager.getLaunchIntentForPackage(it) != null }
        }
    }

    fun dial(context: Context, number: String): Boolean =
        launch(context, Intent(Intent.ACTION_DIAL, "tel:${Uri.encode(number)}".toUri()))

    fun sms(context: Context, number: String): Boolean =
        launch(context, Intent(Intent.ACTION_SENDTO, "smsto:${Uri.encode(number)}".toUri()))

    fun openMessagingApp(context: Context, app: MessagingApp, number: String): Boolean =
        launch(context, Intent(Intent.ACTION_VIEW, app.linkFor(normalizeNumber(number))))

    fun email(context: Context, address: String, subject: String = ""): Boolean {
        val intent = Intent(Intent.ACTION_SENDTO, "mailto:${Uri.encode(address)}".toUri())
        if (subject.isNotBlank()) intent.putExtra(Intent.EXTRA_SUBJECT, subject)
        return launch(context, intent)
    }

    /**
     * Hands the number to whatever else can take it.
     *
     * Deliberately a chooser over `smsto:` rather than a hand-maintained app
     * list: Signal, Messenger and every regional messenger register for it, and
     * none of them need to be named here to appear.
     */
    fun shareToOtherApps(context: Context, number: String): Boolean =
        launch(
            context,
            Intent.createChooser(
                Intent(Intent.ACTION_SENDTO, "smsto:${Uri.encode(number)}".toUri()),
                null
            )
        )

    fun openMap(context: Context, address: String): Boolean =
        launch(context, Intent(Intent.ACTION_VIEW, "geo:0,0?q=${Uri.encode(address)}".toUri()))

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        // A device with no dialer or no mail client is unusual but real; the
        // caller shows a message rather than crashing on a tap.
        false
    }
}
