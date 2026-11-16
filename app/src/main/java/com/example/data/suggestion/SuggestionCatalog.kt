package com.example.data.suggestion

/**
 * How a field's text should be learned, and what it starts out knowing.
 *
 * The previous implementation split every field on commas and newlines. That is
 * right for "Penicillin, Latex" and badly wrong for a treatment plan, where it
 * turned one paragraph into a dozen sentence fragments that then surfaced as
 * suggestions. Splitting is now a property of the field, not a global rule.
 */
enum class FieldKind {
    /** Comma- or semicolon-separated items, each learned on its own. */
    LIST,

    /** A short single value: anaesthesia type, hospital, surgeon. Learned whole. */
    ATOM,

    /** Free prose. Learned whole and only when short enough to be reusable. */
    PROSE
}

data class FieldProfile(
    val key: String,
    val kind: FieldKind,
    /** Shipped vocabulary so a fresh install is useful before it has learned anything. */
    val seeds: List<String> = emptyList(),
    /** Same vocabulary in French, used when the app is in French. */
    val seedsFr: List<String> = emptyList(),
    /** Field whose value predicts this one, for contextual ranking. */
    val predictedBy: String? = null
)

/**
 * Everything the suggestion layer knows about the patient and surgery forms.
 *
 * The seed lists are intentionally short. A long list buries the surgeon's own
 * vocabulary and reads as clutter; a handful of near-universal terms covers the
 * blank-install case and then gets out of the way as real usage accumulates.
 */
object SuggestionCatalog {

    private val profiles: Map<String, FieldProfile> = listOf(
        // Patient — history. Lists of discrete items.
        FieldProfile("patient.medicalHistory", FieldKind.LIST),
        FieldProfile(
            "patient.allergies", FieldKind.LIST,
            seeds = listOf("No known allergies", "Penicillin", "Latex", "Iodine", "NSAIDs"),
            seedsFr = listOf("Aucune allergie connue", "Pénicilline", "Latex", "Iode", "AINS")
        ),
        FieldProfile(
            "patient.chronicDiseases", FieldKind.LIST,
            seeds = listOf("Diabetes mellitus", "Hypertension", "Asthma", "Osteoporosis"),
            seedsFr = listOf("Diabète", "Hypertension artérielle", "Asthme", "Ostéoporose")
        ),
        FieldProfile("patient.previousSurgeries", FieldKind.LIST),
        FieldProfile("patient.medications", FieldKind.LIST),

        // Patient — orthopaedic assessment.
        FieldProfile("patient.diagnosis", FieldKind.ATOM),
        FieldProfile(
            "patient.bodyRegion", FieldKind.ATOM,
            seeds = listOf(
                "Shoulder", "Humerus", "Elbow", "Forearm", "Wrist", "Hand",
                "Hip", "Femur", "Knee", "Tibia", "Ankle", "Foot",
                "Cervical spine", "Thoracic spine", "Lumbar spine", "Pelvis"
            ),
            seedsFr = listOf(
                "Épaule", "Humérus", "Coude", "Avant-bras", "Poignet", "Main",
                "Hanche", "Fémur", "Genou", "Tibia", "Cheville", "Pied",
                "Rachis cervical", "Rachis thoracique", "Rachis lombaire", "Bassin"
            )
        ),
        FieldProfile(
            "patient.traumaMechanism", FieldKind.ATOM,
            seeds = listOf(
                "Fall from standing height", "Fall from height", "Road traffic accident",
                "Sports injury", "Direct blow", "Twisting injury", "Crush injury"
            ),
            seedsFr = listOf(
                "Chute de sa hauteur", "Chute d’un lieu élevé", "Accident de la voie publique",
                "Traumatisme sportif", "Choc direct", "Traumatisme en torsion", "Écrasement"
            )
        ),
        FieldProfile("patient.fractureClassification", FieldKind.ATOM),
        FieldProfile("patient.imagingFindings", FieldKind.PROSE),
        FieldProfile(
            "patient.treatmentPlan", FieldKind.PROSE,
            predictedBy = "patient.diagnosis"
        ),

        // Surgery record.
        // Timeline of a patient's care.
        FieldProfile(
            "timeline.title", FieldKind.ATOM,
            seeds = listOf(
                "Consultation", "Surgery", "Follow-up", "Dressing change",
                "Cast removal", "Suture removal", "X-ray check", "Admission", "Discharge"
            ),
            seedsFr = listOf(
                "Consultation", "Chirurgie", "Contrôle", "Réfection du pansement",
                "Ablation du plâtre", "Ablation des fils", "Contrôle radiologique",
                "Hospitalisation", "Sortie"
            )
        ),
        FieldProfile("timeline.description", FieldKind.PROSE),

        FieldProfile("surgery.hospital", FieldKind.ATOM),
        FieldProfile("surgery.surgeon", FieldKind.ATOM),
        FieldProfile("surgery.assistants", FieldKind.LIST),
        FieldProfile(
            "surgery.anesthesia", FieldKind.ATOM,
            seeds = listOf(
                "General anaesthesia", "Spinal anaesthesia", "Regional block",
                "Locoregional anaesthesia", "Local anaesthesia", "Sedation"
            ),
            seedsFr = listOf(
                "Anesthésie générale", "Rachianesthésie", "Bloc régional",
                "Anesthésie locorégionale", "Anesthésie locale", "Sédation"
            )
        ),
        FieldProfile("surgery.diagnosis", FieldKind.ATOM),
        FieldProfile(
            "surgery.procedure", FieldKind.ATOM,
            predictedBy = "surgery.diagnosis"
        ),
        FieldProfile(
            "surgery.materials", FieldKind.LIST,
            predictedBy = "surgery.procedure"
        ),
        FieldProfile(
            "surgery.complications", FieldKind.LIST,
            seeds = listOf("None", "Superficial infection", "Haematoma", "Neurapraxia"),
            seedsFr = listOf("Aucune", "Infection superficielle", "Hématome", "Neurapraxie")
        ),
        FieldProfile("surgery.postOp", FieldKind.PROSE, predictedBy = "surgery.procedure"),
        FieldProfile("surgery.protocol", FieldKind.PROSE, predictedBy = "surgery.procedure")
    ).associateBy { it.key }

    fun profileFor(fieldKey: String): FieldProfile =
        profiles[fieldKey] ?: FieldProfile(fieldKey, FieldKind.LIST)

    fun seedsFor(fieldKey: String): List<String> {
        val profile = profileFor(fieldKey)
        return if (com.example.data.local.ContentLocale.isFrench && profile.seedsFr.isNotEmpty()) profile.seedsFr else profile.seeds
    }

    /** The field whose current value should bias this field's ranking. */
    fun predictorFor(fieldKey: String): String? = profileFor(fieldKey).predictedBy

    /**
     * Splits a raw entry into the values worth learning.
     *
     * PROSE is capped: a whole operative protocol is not a reusable suggestion,
     * but a two-line post-op instruction is exactly the thing worth offering next
     * time.
     */
    fun itemsToLearn(fieldKey: String, rawValue: String): List<String> {
        val profile = profileFor(fieldKey)
        val parts = when (profile.kind) {
            FieldKind.LIST -> rawValue.split(',', ';', '\n')
            FieldKind.ATOM -> listOf(rawValue)
            FieldKind.PROSE -> listOf(rawValue).filter { it.length <= 160 }
        }
        return parts.mapNotNull { SuggestionEngine.canonicalize(it) }.distinct()
    }
}
