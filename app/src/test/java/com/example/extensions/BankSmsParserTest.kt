package com.example.extensions

import com.example.data.sms.BankSms
import com.example.data.sms.BankSmsInbox
import com.example.data.sms.BankSmsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bank SMS reading: movements are recognised, and codes never get through. */
class BankSmsParserTest {

    @Test
    fun readsAlgeriePosteDebitsAndCreditsWithTheNewBalance() {
        assertEquals(
            BankSms(BankSms.Kind.DEBIT, 30000.0, 93637.47),
            BankSmsParser.parse("Cher client, votre Compte est Débité de -30000.00 DA. Votre nouveau solde est de : 93637.47 DA.")
        )
        assertEquals(
            BankSms(BankSms.Kind.CREDIT, 250000.0, 253609.47),
            BankSmsParser.parse("Cher client, votre Compte est Crédité de +250000.00 DA. Votre nouveau solde est de : 253609.47 DA.")
        )
        assertEquals(1500.5, BankSmsParser.parse("VOTRE COMPTE EST DEBITE DE -1 500,50 DA. VOTRE NOUVEAU SOLDE EST DE : 12 000,00 DA")!!.amount, 0.0)
    }

    @Test
    fun codesAndOtherMessagesAreNeverRead() {
        assertNull(BankSmsParser.parse("ATTENTION aux arnaques, votre code du Virement a partir de votre compte CCP est : 243429"))
        assertNull(BankSmsParser.parse("Débité de -100 DA. Votre code est 1234"))
        assertNull(BankSmsParser.parse("Votre mot de passe temporaire est 9981"))
        assertNull(BankSmsParser.parse("Bonjour, votre colis est arrivé."))
    }

    @Test
    fun liveAndHistoryCopiesOfOneMovementShareAFingerprint() {
        val sms = BankSmsParser.parse("votre Compte est Débité de -90000.00 DA. Votre nouveau solde est de : 3609.47 DA.")!!
        assertEquals(BankSmsInbox.fingerprint(sms, 1_000L), BankSmsInbox.fingerprint(sms, 9_999_000L))
        assertTrue(BankSmsInbox.fingerprint(sms, 0L).contains("3609.47"))
    }
}
