package com.example.extensions

import com.example.data.ai.AssistantReply
import com.example.data.ai.AssistantRequest
import com.example.data.ai.GeminiModels
import com.example.data.ai.ModelFamily
import com.example.data.ai.PatientAssistant
import com.example.data.local.entity.PatientEntity
import com.example.data.local.entity.SurgeryEntity
import com.example.data.local.entity.TimelineEventEntity
import com.example.data.local.model.PatientDetail
import com.example.data.local.model.PatientTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Gemini model choice and the patient assistant's prompt/answer handling. Robolectric only supplies org.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiAssistantTest {

    private val listed = listOf(
        "models/gemini-2.5-flash", "models/gemini-3.5-flash-lite", "models/gemini-3-flash", "models/gemini-3.5-flash",
        "models/gemini-4-flash-preview-09-2026", "models/gemini-3.5-pro", "models/gemini-3.5-flash-image",
        "models/gemini-3.5-flash-preview-tts", "models/text-embedding-004", "models/gemini-3.5-flash-lite-001"
    ).mapNotNull { GeminiModels.parse(it) }

    @Test
    fun specialisedModelsAreIgnored() {
        assertEquals(listOf("gemini-2.5-flash", "gemini-3.5-flash-lite", "gemini-3-flash", "gemini-3.5-flash",
            "gemini-4-flash-preview-09-2026", "gemini-3.5-pro", "gemini-3.5-flash-lite-001"), listed.map { it.id })
        assertEquals(ModelFamily.FLASH_LITE, listed[1].family)
    }

    @Test
    fun newestStableFirstThenFallbacks() {
        val lite = GeminiModels.candidates(listed, "auto:flash-lite")
        assertEquals("gemini-3.5-flash-lite", lite[0])                 // as in the screenshot
        assertEquals("gemini-3.5-flash-lite-001", lite[1])
        assertEquals("gemini-3.5-flash", lite[2])                      // then the next family
        val flash = GeminiModels.candidates(listed, "auto:flash")
        assertEquals("gemini-3.5-flash", flash[0])                     // stable 3.5 before the 4 preview
        assertTrue(flash.indexOf("gemini-4-flash-preview-09-2026") > flash.indexOf("gemini-3.5-pro"))
        assertTrue(flash.contains("gemini-flash-latest"))              // alias as last resort
        val exact = GeminiModels.candidates(listed, "gemini-3.5-pro")
        assertEquals("gemini-3.5-pro", exact[0])
        assertEquals("gemini-flash-latest", GeminiModels.candidates(emptyList(), "auto:flash").first())  // list unreadable
    }

    private fun detail(): PatientDetail {
        val now = 1_800_000_000_000L
        val p = PatientEntity(id = "p1", patientCode = "P-0042", fullName = "Karim Benali", phone = "0550 12 34 56",
            email = "k@x.dz", address = "Oran", insuranceNumber = "123456", emergencyContactName = "Fatima",
            gender = "M", dateOfBirth = 0, diagnosis = "Fracture radius distal", side = "LEFT", allergies = "Pénicilline",
            injuryDate = now - 12 * 86_400_000L, createdAt = 1, updatedAt = 1)
        val s = SurgeryEntity(id = "s1", patientId = "p1", surgeryDate = now - 5 * 86_400_000L, procedure = "Plaque antérieure verrouillée",
            complications = "")
        val e = TimelineEventEntity(id = "e1", patientId = "p1", eventDate = now - 2 * 86_400_000L, title = "Pansement", description = "Écoulement purulent")
        return PatientDetail(p, 45, PatientTimeline.bucket(listOf(e), now), listOf(s), emptyList(), emptyList())
    }

    @Test
    fun contextIsAnonymised() {
        val text = PatientAssistant.anonymisedContext(detail(), 1_800_000_000_000L)
        listOf("Karim", "Benali", "P-0042", "0550", "k@x.dz", "Oran", "123456", "Fatima").forEach {
            assertFalse("'$it' must not be sent", text.contains(it))
        }
        listOf("Âge : 45 ans", "Côté : gauche", "Allergies : Pénicilline", "il y a 12 jours", "Plaque antérieure verrouillée",
            "Écoulement purulent").forEach { assertTrue("missing '$it' in:\n$text", text.contains(it)) }
        val first = PatientAssistant.firstMessage(AssistantRequest.INFECTION, "fièvre 38,5", text)
        assertTrue(first.startsWith("Demande : Infection") && first.contains("fièvre 38,5"))
    }

    @Test
    fun answersAreRead() {
        val q = PatientAssistant.parse("""```json
            {"kind":"questions","questions":[{"question":"Clairance de la créatinine ?","why":"adapter la dose"}]}```""")
        assertEquals("Clairance de la créatinine ?", (q as AssistantReply.Questions).questions.single().question)
        val r = PatientAssistant.parse("""{"kind":"report","report":{"summary":"Infection précoce probable","diagnoses":[{"name":"Infection du site opératoire","likelihood":"élevée","arguments":"écoulement J5"}],
            "investigations":["CRP","Prélèvement"],"management":["Reprise chirurgicale si collection"],
            "treatments":[{"purpose":"infection","dci":"Clindamycine","brandsAlgeria":["Dalacine"],"dosage":"600 mg","posology":"3 fois par jour","duration":"6 semaines","route":"IV puis orale","reimbursement":"peut-être","notes":"allergie pénicilline"}],
            "warnings":["Allergie pénicilline"],"toVerify":["Doses"]}}""") as AssistantReply.Report
        val t = r.report.treatments.single()
        assertEquals("a_verifier", t.reimbursement)            // unknown value → à vérifier
        assertEquals("Dalacine", t.brands)
        assertEquals("CRP\nPrélèvement", r.report.investigations)
        try { PatientAssistant.parse("Désolé, je ne peux pas"); fail() } catch (e: IllegalArgumentException) {}
        val text = PatientAssistant.toText(r.report, "Infection", "gemini-3.5-flash", 0)
        assertTrue(text.contains("Clindamycine (Dalacine)") && text.contains("Remboursement à vérifier"))
    }

    @Test
    fun printableHtmlEscapesEverything() {
        val r = (PatientAssistant.parse("""{"kind":"report","report":{"summary":"<script>x</script>","diagnoses":[],"investigations":[],"management":[],"treatments":[],"warnings":[],"toVerify":[]}}""") as AssistantReply.Report).report
        val html = PatientAssistant.toHtml(r, "Karim <b>", "P-0042", "Douleur", "m", 0)
        assertFalse(html.contains("<script>x"))
        assertTrue(html.contains("&lt;script&gt;") && html.contains("Karim &lt;b&gt;"))
        assertTrue(PatientAssistant.RESPONSE_SCHEMA.toString().contains("\"enum\":[\"remboursable\",\"non_remboursable\",\"a_verifier\"]"))
    }
}
