package com.example.extensions

import com.example.data.extensions.ExtensionPatientRules
import com.example.data.local.entity.BodySide
import com.example.data.local.entity.ImageCategory
import com.example.data.local.entity.PatientEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/** `OrthoTips.patients`: what an extension may write in a record. Robolectric only supplies org.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExtensionPatientRulesTest {

    private val algiers = TimeZone.getTimeZone("Africa/Algiers")
    private val base = PatientEntity(id = "p1", patientCode = "P-0001", fullName = "Karim B.", diagnosis = "Fracture", createdAt = 1, updatedAt = 1)

    private fun rejects(expected: String, block: () -> Unit) {
        try { block(); fail("accepted: $expected") } catch (e: IllegalArgumentException) {
            assertTrue("'${e.message}' should mention '$expected'", e.message!!.contains(expected))
        }
    }

    @Test
    fun partialUpdateChangesOnlyGivenFields() {
        val p = ExtensionPatientRules.apply(base, JSONObject().put("treatmentPlan", "Ostéosynthèse").put("side", "gauche"))
        assertEquals("Ostéosynthèse", p.treatmentPlan)
        assertEquals(BodySide.LEFT.name, p.side)
        assertEquals("Fracture", p.diagnosis)            // untouched
        assertEquals("P-0001", p.patientCode)
    }

    @Test
    fun datesAcceptMillisIsoOrNull() {
        val p = ExtensionPatientRules.apply(base, JSONObject().put("dateOfBirth", "1980-05-01").put("injuryDate", 1_700_000_000_000L), algiers)
        val cal = java.util.Calendar.getInstance(algiers).apply { timeInMillis = p.dateOfBirth!! }
        assertEquals(listOf(1980, 4, 1, 0), listOf(cal.get(1), cal.get(2), cal.get(5), cal.get(11)))
        assertEquals(1_700_000_000_000L, p.injuryDate)
        assertEquals(null, ExtensionPatientRules.apply(p, JSONObject().put("dateOfBirth", JSONObject.NULL)).dateOfBirth)
        rejects("YYYY-MM-DD") { ExtensionPatientRules.apply(base, JSONObject().put("dateOfBirth", "01/05/1980")) }
        rejects("not a valid date") { ExtensionPatientRules.apply(base, JSONObject().put("dateOfBirth", "1980-02-31"), algiers) }
    }

    @Test
    fun unknownReadOnlyAndInvalidFieldsAreRefused() {
        rejects("Unknown patient field 'age'") { ExtensionPatientRules.apply(base, JSONObject().put("age", 40)) }
        rejects("cannot be changed") { ExtensionPatientRules.apply(base, JSONObject().put("id", "x")) }
        rejects("cannot be changed") { ExtensionPatientRules.apply(base, JSONObject().put("isArchived", true)) }
        rejects("too long") { ExtensionPatientRules.apply(base, JSONObject().put("fullName", "x".repeat(201))) }
        rejects("fullName is required") { ExtensionPatientRules.apply(base, JSONObject().put("fullName", "   ")) }
        rejects("must be text") { ExtensionPatientRules.apply(base, JSONObject().put("diagnosis", JSONObject())) }
        rejects("Field 'side'") { ExtensionPatientRules.apply(base, JSONObject().put("side", "up")) }
    }

    @Test
    fun sidesCategoriesAndMediaTypes() {
        assertEquals(BodySide.RIGHT, ExtensionPatientRules.side("droite"))
        assertEquals(BodySide.BILATERAL, ExtensionPatientRules.side("Bilatéral"))
        assertEquals(BodySide.NOT_APPLICABLE, ExtensionPatientRules.side(null))
        assertEquals(ImageCategory.RADIOLOGY, ExtensionPatientRules.category("radiology"))
        assertEquals(ImageCategory.OTHER, ExtensionPatientRules.category(null))
        rejects("Unknown media category") { ExtensionPatientRules.category("xray") }
        assertEquals("image", ExtensionPatientRules.mediaKind("image/png"))
        assertEquals("video", ExtensionPatientRules.mediaKind("video/mp4"))
        rejects("Unsupported media type") { ExtensionPatientRules.mediaKind("image/svg+xml") }
        rejects("Unsupported media type") { ExtensionPatientRules.mediaKind("application/pdf") }
        assertEquals("mov", ExtensionPatientRules.videoExtension("video/quicktime"))
    }

    @Test
    fun jsonExposesEveryWritableField() {
        val json = ExtensionPatientRules.toJson(base)
        (ExtensionPatientRules.TEXT_FIELDS.keys + ExtensionPatientRules.DATE_FIELDS + "side" + "id" + "createdAt" + "updatedAt" + "isArchived")
            .forEach { assertTrue("missing $it", json.has(it)) }
    }
}
