package com.example.data.sms

/**
 * A bank movement read from an SMS: what the extension receives.
 * [balance] is the new balance the bank reports, when the message states it.
 */
data class BankSms(
    val kind: Kind,
    val amount: Double,
    val balance: Double?,
    val currency: String = "DZD"
) {
    enum class Kind { DEBIT, CREDIT }
}

/**
 * Recognises account-movement SMS such as Algérie Poste's:
 *
 *     Cher client, votre Compte est Débité de -30000.00 DA.
 *     Votre nouveau solde est de : 93637.47 DA.
 *
 * Deliberately narrow: a message is accepted only when it states a debit or a
 * credit with an amount in dinars, and it is refused outright when it mentions
 * a code, a password or an OTP — so transfer confirmation codes are never
 * stored or passed to an extension. Pure Kotlin, unit-tested.
 */
object BankSmsParser {

    private val SECRET = Regex("\\b(code|otp|mot\\s+de\\s+passe|password|pin)\\b", RegexOption.IGNORE_CASE)
    private val MOVEMENT = Regex(
        "(D[EÉeé]BIT[EÉeé]|CR[EÉeé]DIT[EÉeé])\\s+de\\s*([-+]?)\\s*(\\d[\\d\\s.,]*?)\\s*(DA|DZD)\\b",
        RegexOption.IGNORE_CASE
    )
    private val BALANCE = Regex(
        "solde\\s+(?:est\\s+)?de\\s*:?\\s*(-?\\s*\\d[\\d\\s.,]*?)\\s*(DA|DZD)\\b",
        RegexOption.IGNORE_CASE
    )

    fun parse(body: String): BankSms? {
        val text = body.replace('\u00A0', ' ')
        if (SECRET.containsMatchIn(text)) return null
        val movement = MOVEMENT.find(text) ?: return null
        val amount = number(movement.groupValues[3]) ?: return null
        if (amount <= 0.0) return null
        val kind = if (movement.groupValues[1].first().uppercaseChar() == 'D') BankSms.Kind.DEBIT else BankSms.Kind.CREDIT
        val balance = BALANCE.find(text, movement.range.last)?.groupValues?.get(1)?.let(::number)
        return BankSms(kind, amount, balance)
    }

    /** "150000.00", "150 000,00", "1,500.00", "-3609.47" → Double, rounded to cents. */
    internal fun number(raw: String): Double? {
        var s = raw.replace(Regex("\\s"), "").trimEnd('.', ',')
        val negative = s.startsWith("-")
        s = s.removePrefix("-")
        s = when {
            s.contains(',') && s.contains('.') ->
                if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            s.contains(',') -> s.replace(',', '.')
            else -> s
        }
        val value = s.toDoubleOrNull() ?: return null
        return Math.round((if (negative) -value else value) * 100) / 100.0
    }
}
