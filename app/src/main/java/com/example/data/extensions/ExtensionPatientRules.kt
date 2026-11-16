package com.example.data.extensions

import com.example.data.local.entity.BodySide
import com.example.data.local.entity.ImageCategory
import com.example.data.local.entity.PatientEntity
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * What an extension may write in a patient record through `OrthoTips.patients`
 * (extension guide, section 5): a whitelist of fields with their types and
 * lengths. Plain Kotlin + org.json, unit-tested on the JVM. Every refusal throws
 * [IllegalArgumentException] with the message the extension receives.
 */
object ExtensionPatientRules {

    /** Text fields and their maximum length. */
    val TEXT_FIELDS: Map<String, Int> = linkedMapOf(
        "patientCode" to 40, "fullName" to 200, "gender" to 40,
        "phone" to 60, "email" to 200, "address" to 1000,
        "emergencyContactName" to 200, "emergencyContactPhone" to 60,
        "insuranceProvider" to 200, "insuranceNumber" to 100,
        "medicalHistory" to 20_000, "allergies" to 5_000, "chronicDiseases" to 5_000,
        "previousSurgeries" to 20_000, "medications" to 5_000, "generalNotes" to 20_000,
        "diagnosis" to 1_000, "bodyRegion" to 200, "traumaMechanism" to 2_000,
        "fractureClassification" to 1_000, "imagingFindings" to 20_000, "treatmentPlan" to 20_000
    )
    /** Dates: epoch milliseconds, or "YYYY-MM-DD" (local midnight), or null. */
    val DATE_FIELDS = setOf("dateOfBirth", "injuryDate")
    /** Set by the app; an extension cannot write them. */
    val READ_ONLY = setOf("id", "createdAt", "updatedAt", "isArchived", "media")

    private val SIDES = mapOf(
        "left" to BodySide.LEFT, "gauche" to BodySide.LEFT,
        "right" to BodySide.RIGHT, "droite" to BodySide.RIGHT, "droit" to BodySide.RIGHT,
        "bilateral" to BodySide.BILATERAL, "bilatéral" to BodySide.BILATERAL, "bilatérale" to BodySide.BILATERAL,
        "not_applicable" to BodySide.NOT_APPLICABLE, "" to BodySide.NOT_APPLICABLE
    )
    private val ISO_DATE = Regex("(\\d{4})-(\\d{2})-(\\d{2})")

    const val MAX_MEDIA_BYTES = 30L * 1024 * 1024
    const val MAX_SEARCH_RESULTS = 200

    /**
     * [base] with [fields] applied. Only the fields present change; unknown or
     * read-only fields are refused; `fullName` can never become empty.
     */
    fun apply(base: PatientEntity, fields: JSONObject, zone: TimeZone = TimeZone.getDefault()): PatientEntity {
        var p = base
        for (key in fields.keys()) {
            if (key in READ_ONLY) throw IllegalArgumentException("Field '$key' cannot be changed by an extension")
            val value = fields.opt(key)
            p = when {
                key in TEXT_FIELDS -> setText(p, key, text(key, value))
                key in DATE_FIELDS -> setDate(p, key, date(key, value, zone))
                key == "side" -> p.copy(side = side(value).name)
                else -> throw IllegalArgumentException("Unknown patient field '$key'")
            }
        }
        if (p.fullName.isBlank()) throw IllegalArgumentException("fullName is required")
        return p
    }

    private fun text(key: String, value: Any?): String {
        val s = when (value) {
            null, JSONObject.NULL -> ""
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> throw IllegalArgumentException("Field '$key' must be text")
        }
        val max = TEXT_FIELDS.getValue(key)
        if (s.length > max) throw IllegalArgumentException("Field '$key' is too long (max $max characters)")
        return if (key == "patientCode" || key == "fullName") s.trim() else s
    }

    private fun date(key: String, value: Any?, zone: TimeZone): Long? = when (value) {
        null, JSONObject.NULL -> null
        is Number -> value.toLong().takeIf { value.toDouble().isFinite() }
            ?: throw IllegalArgumentException("Field '$key' must be a date")
        is String -> if (value.isBlank()) null else {
            val m = ISO_DATE.matchEntire(value.trim()) ?: throw IllegalArgumentException("Field '$key' must be epoch milliseconds or \"YYYY-MM-DD\"")
            val (y, mo, d) = m.destructured
            Calendar.getInstance(zone).apply {
                isLenient = false
                clear()
                set(y.toInt(), mo.toInt() - 1, d.toInt(), 0, 0, 0)
            }.let { runCatching { it.timeInMillis }.getOrElse { throw IllegalArgumentException("Field '$key' is not a valid date") } }
        }
        else -> throw IllegalArgumentException("Field '$key' must be a date")
    }

    fun side(value: Any?): BodySide {
        val s = (if (value == null || value == JSONObject.NULL) "" else value.toString()).trim()
        BodySide.entries.firstOrNull { it.name.equals(s, ignoreCase = true) }?.let { return it }
        return SIDES[s.lowercase()]
            ?: throw IllegalArgumentException("Field 'side' must be LEFT, RIGHT, BILATERAL or NOT_APPLICABLE")
    }

    fun category(value: String?): ImageCategory {
        if (value.isNullOrBlank()) return ImageCategory.OTHER
        return ImageCategory.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown media category '$value' (RADIOLOGY, CLINICAL_PHOTO, DOCUMENT_SCAN, OTHER)")
    }

    /** "image" or "video", from the MIME type; anything else is refused. */
    fun mediaKind(mimeType: String?): String {
        val m = mimeType.orEmpty().trim().lowercase()
        return when {
            m.startsWith("image/") && m != "image/svg+xml" -> "image"
            m.startsWith("video/") -> "video"
            else -> throw IllegalArgumentException("Unsupported media type '$mimeType' (images and videos only)")
        }
    }

    /** File extension for a stored video. */
    fun videoExtension(mimeType: String): String = when (mimeType.lowercase().substringAfter('/')) {
        "webm" -> "webm"; "3gpp" -> "3gp"; "quicktime" -> "mov"; else -> "mp4"
    }

    fun toJson(p: PatientEntity): JSONObject = JSONObject().apply {
        put("id", p.id)
        TEXT_FIELDS.keys.forEach { key -> put(key, textOf(p, key)) }
        put("dateOfBirth", p.dateOfBirth ?: JSONObject.NULL)
        put("injuryDate", p.injuryDate ?: JSONObject.NULL)
        put("side", p.side)
        put("isArchived", p.isArchived)
        put("createdAt", p.createdAt)
        put("updatedAt", p.updatedAt)
    }

    fun summaryJson(p: PatientEntity): JSONObject = JSONObject()
        .put("id", p.id).put("patientCode", p.patientCode).put("fullName", p.fullName)
        .put("dateOfBirth", p.dateOfBirth ?: JSONObject.NULL).put("diagnosis", p.diagnosis)
        .put("bodyRegion", p.bodyRegion).put("side", p.side).put("isArchived", p.isArchived)
        .put("updatedAt", p.updatedAt)

    private fun textOf(p: PatientEntity, key: String): String = when (key) {
        "patientCode" -> p.patientCode; "fullName" -> p.fullName; "gender" -> p.gender
        "phone" -> p.phone; "email" -> p.email; "address" -> p.address
        "emergencyContactName" -> p.emergencyContactName; "emergencyContactPhone" -> p.emergencyContactPhone
        "insuranceProvider" -> p.insuranceProvider; "insuranceNumber" -> p.insuranceNumber
        "medicalHistory" -> p.medicalHistory; "allergies" -> p.allergies; "chronicDiseases" -> p.chronicDiseases
        "previousSurgeries" -> p.previousSurgeries; "medications" -> p.medications; "generalNotes" -> p.generalNotes
        "diagnosis" -> p.diagnosis; "bodyRegion" -> p.bodyRegion; "traumaMechanism" -> p.traumaMechanism
        "fractureClassification" -> p.fractureClassification; "imagingFindings" -> p.imagingFindings
        "treatmentPlan" -> p.treatmentPlan
        else -> error(key)
    }

    private fun setText(p: PatientEntity, key: String, v: String): PatientEntity = when (key) {
        "patientCode" -> p.copy(patientCode = v); "fullName" -> p.copy(fullName = v); "gender" -> p.copy(gender = v)
        "phone" -> p.copy(phone = v); "email" -> p.copy(email = v); "address" -> p.copy(address = v)
        "emergencyContactName" -> p.copy(emergencyContactName = v); "emergencyContactPhone" -> p.copy(emergencyContactPhone = v)
        "insuranceProvider" -> p.copy(insuranceProvider = v); "insuranceNumber" -> p.copy(insuranceNumber = v)
        "medicalHistory" -> p.copy(medicalHistory = v); "allergies" -> p.copy(allergies = v); "chronicDiseases" -> p.copy(chronicDiseases = v)
        "previousSurgeries" -> p.copy(previousSurgeries = v); "medications" -> p.copy(medications = v); "generalNotes" -> p.copy(generalNotes = v)
        "diagnosis" -> p.copy(diagnosis = v); "bodyRegion" -> p.copy(bodyRegion = v); "traumaMechanism" -> p.copy(traumaMechanism = v)
        "fractureClassification" -> p.copy(fractureClassification = v); "imagingFindings" -> p.copy(imagingFindings = v)
        "treatmentPlan" -> p.copy(treatmentPlan = v)
        else -> error(key)
    }

    private fun setDate(p: PatientEntity, key: String, v: Long?): PatientEntity =
        if (key == "dateOfBirth") p.copy(dateOfBirth = v) else p.copy(injuryDate = v)
}
