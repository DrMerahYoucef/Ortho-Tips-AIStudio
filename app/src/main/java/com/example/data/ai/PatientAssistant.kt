package com.example.data.ai

import com.example.data.local.entity.BodySide
import com.example.data.local.model.PatientDetail
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** What the surgeon asks the assistant for. */
enum class AssistantRequest { DIAGNOSIS, PAIN, INFECTION, PROCEDURE, FREE }

data class AssistantQuestion(val question: String, val why: String)

data class AssistantDiagnosis(val name: String, val likelihood: String, val arguments: String)

/** One proposed drug. [reimbursement]: "remboursable", "non_remboursable" or "a_verifier". */
data class AssistantTreatment(
    val purpose: String,
    val dci: String,
    val brands: String,
    val dosage: String,
    val posology: String,
    val duration: String,
    val route: String,
    val reimbursement: String,
    val notes: String
)

data class AssistantReport(
    val summary: String,
    val diagnoses: List<AssistantDiagnosis>,
    val investigations: String,
    val management: String,
    val treatments: List<AssistantTreatment>,
    val warnings: String,
    val toVerify: String
)

sealed class AssistantReply {
    data class Questions(val questions: List<AssistantQuestion>) : AssistantReply()
    data class Report(val report: AssistantReport) : AssistantReply()
}

/**
 * The patient assistant: what is sent to Gemini and how its answer is read.
 * Pure Kotlin + org.json, unit-tested.
 *
 * Privacy: [anonymisedContext] never contains the patient's name, code,
 * contact details, address or insurance — only age, sex and clinical data,
 * which the surgeon sees and can edit before sending.
 */
object PatientAssistant {

    val REIMBURSEMENT_VALUES = listOf("remboursable", "non_remboursable", "a_verifier")

    /** The anonymised clinical summary sent to Gemini (shown and editable first). */
    fun anonymisedContext(detail: PatientDetail, now: Long = System.currentTimeMillis()): String {
        val p = detail.patient
        val lines = mutableListOf<String>()
        fun add(label: String, value: String?) { if (!value.isNullOrBlank()) lines += "$label : ${value.trim()}" }
        add("Âge", detail.age?.let { "$it ans" })
        add("Sexe", p.gender)
        add("Région", p.bodyRegion)
        add("Côté", when (p.side) {
            BodySide.LEFT.name -> "gauche"; BodySide.RIGHT.name -> "droit"; BodySide.BILATERAL.name -> "bilatéral"; else -> null
        })
        add("Diagnostic retenu", p.diagnosis)
        add("Classification", p.fractureClassification)
        add("Mécanisme", p.traumaMechanism)
        add("Date du traumatisme", p.injuryDate?.let { relative(it, now) })
        add("Antécédents médicaux", p.medicalHistory)
        add("Allergies", p.allergies)
        add("Maladies chroniques", p.chronicDiseases)
        add("Chirurgies antérieures", p.previousSurgeries)
        add("Traitements en cours", p.medications)
        add("Imagerie", p.imagingFindings)
        add("Plan de traitement", p.treatmentPlan)
        add("Notes", p.generalNotes)
        detail.surgeries.sortedBy { it.surgeryDate }.forEach { s ->
            val parts = listOf(
                s.procedure.takeIf { it.isNotBlank() },
                s.diagnosis.takeIf { it.isNotBlank() }?.let { "pour $it" },
                s.anesthesiaType.takeIf { it.isNotBlank() }?.let { "anesthésie : $it" },
                s.materialsUsed.takeIf { it.isNotBlank() }?.let { "matériel : $it" },
                s.complications.takeIf { it.isNotBlank() }?.let { "complications : $it" }
            ).filterNotNull()
            if (parts.isNotEmpty()) lines += "Intervention (${relative(s.surgeryDate, now)}) : ${parts.joinToString(" ; ")}"
        }
        detail.timeline.all.sortedByDescending { it.eventDate }.take(6).sortedBy { it.eventDate }.forEach { e ->
            val text = listOf(e.title, e.description).filter { it.isNotBlank() }.joinToString(" — ")
            if (text.isNotBlank()) lines += "Suivi (${relative(e.eventDate, now)}) : $text"
        }
        return if (lines.isEmpty()) "Aucune donnée clinique renseignée." else lines.joinToString("\n")
    }

    /** "il y a 12 jours", "aujourd’hui", "dans 3 jours": dates without the calendar date itself. */
    fun relative(epochMs: Long, now: Long): String {
        val days = TimeUnit.MILLISECONDS.toDays(now - epochMs)
        return when {
            days == 0L -> "aujourd’hui"
            days > 0 -> "il y a $days jour" + (if (days > 1) "s" else "")
            else -> "dans ${-days} jour" + (if (days < -1) "s" else "")
        }
    }

    fun requestLabel(request: AssistantRequest): String = when (request) {
        AssistantRequest.DIAGNOSIS -> "Diagnostic probable et conduite à tenir"
        AssistantRequest.PAIN -> "Traitement adapté de la douleur"
        AssistantRequest.INFECTION -> "Infection : antibiothérapie adaptée"
        AssistantRequest.PROCEDURE -> "Intervention : préparation et suites (antibioprophylaxie, thromboprophylaxie, antalgie)"
        AssistantRequest.FREE -> "Question libre"
    }

    /** The first message of a conversation. */
    fun firstMessage(request: AssistantRequest, question: String, context: String): String = buildString {
        append("Demande : ").append(requestLabel(request)).append('\n')
        if (question.isNotBlank()) append("Précision du chirurgien : ").append(question.trim()).append('\n')
        append("\nDonnées du patient (anonymisées) :\n").append(context.trim())
    }

    fun answersMessage(questions: List<AssistantQuestion>, answers: List<String>): String = buildString {
        append("Réponses du chirurgien :\n")
        questions.forEachIndexed { i, q ->
            val a = answers.getOrNull(i).orEmpty().trim()
            append("- ").append(q.question).append(" → ").append(a.ifEmpty { "inconnu" }).append('\n')
        }
        append("Produis maintenant le rapport, ou d’autres questions seulement si c’est indispensable.")
    }

    const val SKIP_MESSAGE = "Je n’ai pas ces informations. Produis le rapport quand même, en indiquant tes hypothèses et ce qui reste à vérifier dans toVerify."

    fun refineMessage(text: String) = "Modifie le rapport selon cette demande du chirurgien, et renvoie le rapport complet : ${text.trim()}"

    val SYSTEM_INSTRUCTION = """
Tu es un assistant d’aide à la décision pour un chirurgien orthopédiste qui exerce en Algérie. Tu t’adresses à un médecin : sois précis, concis et en français.
Règles :
1. Base-toi uniquement sur les données fournies. N’invente aucun fait sur le patient.
2. Si une information indispensable à une proposition sûre manque (allergies, fonction rénale, poids, grossesse, anticoagulants, diabète, gravité, résultats d’examens…), réponds avec kind = "questions" : au plus 5 questions courtes, chacune avec la raison. Ne pose pas de question dont la réponse figure déjà dans les données.
3. Sinon, réponds avec kind = "report".
4. Diagnostics : des hypothèses, jamais une certitude, chacune avec une probabilité ("élevée", "moyenne", "faible") et ses arguments.
5. Traitements : uniquement des médicaments disponibles en Algérie. Donne la DCI, des noms commerciaux couramment disponibles en Algérie (laisse vide si tu n’es pas sûr : n’en invente jamais), le dosage, la posologie adaptée au patient (âge, poids, fonction rénale si connus), la durée et la voie.
6. Remboursement : "remboursable" seulement si, à ta connaissance, le médicament figure sur la nomenclature des médicaments remboursables par la sécurité sociale algérienne (CNAS / CASNOS) ; "non_remboursable" si tu sais qu’il n’y figure pas ; sinon "a_verifier". Dans le doute, "a_verifier".
7. Signale les contre-indications et interactions liées aux antécédents, allergies et traitements du patient dans warnings.
8. Mets dans toVerify tout ce que le chirurgien doit vérifier avant d’appliquer (doses, remboursement, disponibilité, examens).
9. Pour une demande ciblée (douleur, infection, intervention), concentre le rapport sur cette demande ; les diagnostics peuvent alors être brefs.
""".trimIndent()

    /** Gemini response schema (OpenAPI subset, as the v1beta API expects). */
    val RESPONSE_SCHEMA: JSONObject by lazy {
        fun str(desc: String? = null) = JSONObject().put("type", "STRING").apply { if (desc != null) put("description", desc) }
        fun enumOf(vararg v: String) = JSONObject().put("type", "STRING").put("enum", JSONArray(v.toList()))
        fun obj(props: JSONObject, required: List<String>) = JSONObject().put("type", "OBJECT").put("properties", props).put("required", JSONArray(required))
        fun arr(items: JSONObject) = JSONObject().put("type", "ARRAY").put("items", items)
        val question = obj(JSONObject().put("question", str()).put("why", str()), listOf("question", "why"))
        val diagnosis = obj(JSONObject().put("name", str()).put("likelihood", enumOf("élevée", "moyenne", "faible")).put("arguments", str()),
            listOf("name", "likelihood", "arguments"))
        val treatment = obj(
            JSONObject().put("purpose", enumOf("douleur", "infection", "thromboprophylaxie", "intervention", "autre"))
                .put("dci", str()).put("brandsAlgeria", arr(str())).put("dosage", str()).put("posology", str())
                .put("duration", str()).put("route", str())
                .put("reimbursement", enumOf("remboursable", "non_remboursable", "a_verifier")).put("notes", str()),
            listOf("purpose", "dci", "brandsAlgeria", "dosage", "posology", "duration", "route", "reimbursement", "notes")
        )
        val report = obj(
            JSONObject().put("summary", str()).put("diagnoses", arr(diagnosis)).put("investigations", arr(str()))
                .put("management", arr(str())).put("treatments", arr(treatment)).put("warnings", arr(str())).put("toVerify", arr(str())),
            listOf("summary", "diagnoses", "investigations", "management", "treatments", "warnings", "toVerify")
        )
        obj(JSONObject().put("kind", enumOf("questions", "report")).put("questions", arr(question)).put("report", report), listOf("kind"))
    }

    /** Reads Gemini's JSON answer (code fences tolerated). Throws IllegalArgumentException when unusable. */
    fun parse(text: String): AssistantReply {
        val json = try {
            JSONObject(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        } catch (e: Exception) {
            throw IllegalArgumentException("Réponse illisible de Gemini")
        }
        val questions = json.optJSONArray("questions")
        if (json.optString("kind") == "questions" && questions != null && questions.length() > 0) {
            return AssistantReply.Questions((0 until questions.length()).mapNotNull { i ->
                questions.optJSONObject(i)?.let { AssistantQuestion(it.optString("question"), it.optString("why")) }
                    ?.takeIf { it.question.isNotBlank() }
            })
        }
        val r = json.optJSONObject("report") ?: throw IllegalArgumentException("Réponse incomplète de Gemini")
        fun lines(key: String) = r.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.joinToString("\n") }.orEmpty()
        val diagnoses = r.optJSONArray("diagnoses")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            .map { AssistantDiagnosis(it.optString("name"), it.optString("likelihood"), it.optString("arguments")) }
        val treatments = r.optJSONArray("treatments")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty().map { t ->
            val brands = t.optJSONArray("brandsAlgeria")?.let { b -> (0 until b.length()).map { b.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
            AssistantTreatment(
                purpose = t.optString("purpose"), dci = t.optString("dci"), brands = brands.joinToString(", "),
                dosage = t.optString("dosage"), posology = t.optString("posology"), duration = t.optString("duration"),
                route = t.optString("route"),
                reimbursement = t.optString("reimbursement").takeIf { it in REIMBURSEMENT_VALUES } ?: "a_verifier",
                notes = t.optString("notes")
            )
        }
        return AssistantReply.Report(AssistantReport(r.optString("summary"), diagnoses, lines("investigations"), lines("management"),
            treatments, lines("warnings"), lines("toVerify")))
    }

    fun reimbursementLabel(value: String) = when (value) {
        "remboursable" -> "Remboursable"; "non_remboursable" -> "Non remboursable"; else -> "Remboursement à vérifier"
    }

    /** Plain text, for copying and for the patient's notes. */
    fun toText(report: AssistantReport, requestLabel: String, model: String, at: Long): String = buildString {
        append("Assistant IA (").append(model).append(") — ").append(date(at)).append(" — à vérifier\n")
        append("Demande : ").append(requestLabel).append("\n\n")
        if (report.summary.isNotBlank()) append(report.summary.trim()).append("\n\n")
        if (report.diagnoses.isNotEmpty()) {
            append("Diagnostics probables :\n")
            report.diagnoses.forEach { append("- ").append(it.name).append(" (").append(it.likelihood).append(")")
                if (it.arguments.isNotBlank()) append(" : ").append(it.arguments); append('\n') }
            append('\n')
        }
        section("Examens complémentaires", report.investigations)
        section("Conduite à tenir", report.management)
        if (report.treatments.isNotEmpty()) {
            append("Traitements :\n")
            report.treatments.forEach { t ->
                append("- ").append(t.dci)
                if (t.brands.isNotBlank()) append(" (").append(t.brands).append(")")
                listOf(t.dosage, t.route, t.posology, t.duration).filter { it.isNotBlank() }.forEach { append(" ; ").append(it) }
                append(" — ").append(reimbursementLabel(t.reimbursement))
                if (t.notes.isNotBlank()) append(". ").append(t.notes)
                append('\n')
            }
            append('\n')
        }
        section("Précautions", report.warnings)
        section("À vérifier", report.toVerify)
    }.trim()

    private fun StringBuilder.section(title: String, body: String) {
        if (body.isBlank()) return
        append(title).append(" :\n")
        body.lines().filter { it.isNotBlank() }.forEach { append("- ").append(it.trim().removePrefix("- ")).append('\n') }
        append('\n')
    }

    /** A printable page. The patient's name is added here, on the phone only: it was never sent. */
    fun toHtml(report: AssistantReport, patientName: String, patientCode: String, requestLabel: String, model: String, at: Long): String {
        fun e(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun list(body: String) = body.lines().filter { it.isNotBlank() }.joinToString("") { "<li>${e(it.trim().removePrefix("- "))}</li>" }
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html lang=\"fr\"><head><meta charset=\"utf-8\"><style>")
        sb.append("body{font-family:sans-serif;font-size:11pt;color:#111;margin:24px}h1{font-size:16pt;margin:0}h2{font-size:12pt;margin:18px 0 6px;border-bottom:1px solid #ccc}")
        sb.append(".meta{color:#555;font-size:9pt;margin:4px 0 12px}table{border-collapse:collapse;width:100%;font-size:10pt}td,th{border:1px solid #bbb;padding:4px;vertical-align:top;text-align:left}")
        sb.append(".warn{background:#fff4e5;border:1px solid #f0b46a;padding:8px;font-size:9pt;margin-top:16px}</style></head><body>")
        sb.append("<h1>").append(e(patientName)).append("</h1><div class=\"meta\">").append(e(patientCode)).append(" · ").append(e(date(at)))
            .append(" · ").append(e(requestLabel)).append("</div>")
        if (report.summary.isNotBlank()) sb.append("<p>").append(e(report.summary)).append("</p>")
        if (report.diagnoses.isNotEmpty()) {
            sb.append("<h2>Diagnostics probables</h2><ul>")
            report.diagnoses.forEach { sb.append("<li><b>").append(e(it.name)).append("</b> (").append(e(it.likelihood)).append(")")
                if (it.arguments.isNotBlank()) sb.append(" — ").append(e(it.arguments)); sb.append("</li>") }
            sb.append("</ul>")
        }
        if (report.investigations.isNotBlank()) sb.append("<h2>Examens complémentaires</h2><ul>").append(list(report.investigations)).append("</ul>")
        if (report.management.isNotBlank()) sb.append("<h2>Conduite à tenir</h2><ul>").append(list(report.management)).append("</ul>")
        if (report.treatments.isNotEmpty()) {
            sb.append("<h2>Traitements</h2><table><tr><th>Médicament</th><th>Dosage / voie</th><th>Posologie</th><th>Durée</th><th>Remboursement</th></tr>")
            report.treatments.forEach { t ->
                sb.append("<tr><td><b>").append(e(t.dci)).append("</b>")
                if (t.brands.isNotBlank()) sb.append("<br>").append(e(t.brands))
                if (t.notes.isNotBlank()) sb.append("<br><i>").append(e(t.notes)).append("</i>")
                sb.append("</td><td>").append(e(listOf(t.dosage, t.route).filter { it.isNotBlank() }.joinToString(" · ")))
                    .append("</td><td>").append(e(t.posology)).append("</td><td>").append(e(t.duration))
                    .append("</td><td>").append(e(reimbursementLabel(t.reimbursement))).append("</td></tr>")
            }
            sb.append("</table>")
        }
        if (report.warnings.isNotBlank()) sb.append("<h2>Précautions</h2><ul>").append(list(report.warnings)).append("</ul>")
        if (report.toVerify.isNotBlank()) sb.append("<h2>À vérifier</h2><ul>").append(list(report.toVerify)).append("</ul>")
        sb.append("<div class=\"warn\">Suggestions générées par intelligence artificielle (").append(e(model))
            .append("), relues par le médecin. Elles ne remplacent pas son jugement. Le remboursement CNAS/CASNOS est à confirmer.</div>")
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun date(at: Long) = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(at))
}
