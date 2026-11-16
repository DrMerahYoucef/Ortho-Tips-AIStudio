package com.example.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.example.utils.PatientChartPdfOptions
import com.example.utils.SurgeryPdfOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.pdfOptionsDataStore by preferencesDataStore(name = "pdf_export_options")

/**
 * Remembers which sections the surgeon last chose to include when exporting a surgery
 * protocol or a patient chart to PDF, so the "Include in PDF" checkboxes start from
 * whatever was picked last time instead of resetting to everything-on each export.
 */
class PdfOptionsRepository(private val context: Context) {

    private object Keys {
        val S_HOSPITAL = booleanPreferencesKey("surgery_include_hospital")
        val S_SURGEON = booleanPreferencesKey("surgery_include_surgeon")
        val S_ASSISTANTS = booleanPreferencesKey("surgery_include_assistants")
        val S_ANESTHESIA = booleanPreferencesKey("surgery_include_anesthesia")
        val S_DIAGNOSIS = booleanPreferencesKey("surgery_include_diagnosis")
        val S_PROCEDURE = booleanPreferencesKey("surgery_include_procedure")
        val S_STEPS = booleanPreferencesKey("surgery_include_steps")
        val S_MATERIALS = booleanPreferencesKey("surgery_include_materials")
        val S_COMPLICATIONS = booleanPreferencesKey("surgery_include_complications")
        val S_POSTOP = booleanPreferencesKey("surgery_include_postop")
        val S_SIGNATURE = booleanPreferencesKey("surgery_include_signature")
        val S_QR = booleanPreferencesKey("surgery_include_qr")

        val P_CONTACT = booleanPreferencesKey("chart_include_contact")
        val P_INSURANCE = booleanPreferencesKey("chart_include_insurance")
        val P_MEDICAL_HISTORY = booleanPreferencesKey("chart_include_medical_history")
        val P_ALLERGIES = booleanPreferencesKey("chart_include_allergies")
        val P_CHRONIC = booleanPreferencesKey("chart_include_chronic")
        val P_PREV_SURGERIES = booleanPreferencesKey("chart_include_prev_surgeries")
        val P_MEDICATIONS = booleanPreferencesKey("chart_include_medications")
        val P_NOTES = booleanPreferencesKey("chart_include_notes")
        val P_ORTHO_INFO = booleanPreferencesKey("chart_include_ortho_info")
        val P_TIMELINE = booleanPreferencesKey("chart_include_timeline")
        val P_SURGERIES = booleanPreferencesKey("chart_include_surgeries")
        val P_QR = booleanPreferencesKey("chart_include_qr")
    }

    val surgeryOptionsFlow: Flow<SurgeryPdfOptions> = context.pdfOptionsDataStore.data.map { prefs ->
        SurgeryPdfOptions(
            includeHospital = prefs[Keys.S_HOSPITAL] ?: true,
            includeSurgeon = prefs[Keys.S_SURGEON] ?: true,
            includeAssistants = prefs[Keys.S_ASSISTANTS] ?: true,
            includeAnesthesia = prefs[Keys.S_ANESTHESIA] ?: true,
            includeDiagnosis = prefs[Keys.S_DIAGNOSIS] ?: true,
            includeProcedure = prefs[Keys.S_PROCEDURE] ?: true,
            includeOperativeSteps = prefs[Keys.S_STEPS] ?: true,
            includeMaterials = prefs[Keys.S_MATERIALS] ?: true,
            includeComplications = prefs[Keys.S_COMPLICATIONS] ?: true,
            includePostOp = prefs[Keys.S_POSTOP] ?: true,
            includeSignature = prefs[Keys.S_SIGNATURE] ?: true,
            includeQrCode = prefs[Keys.S_QR] ?: true
        )
    }

    suspend fun saveSurgeryOptions(options: SurgeryPdfOptions) {
        context.pdfOptionsDataStore.edit { prefs ->
            prefs[Keys.S_HOSPITAL] = options.includeHospital
            prefs[Keys.S_SURGEON] = options.includeSurgeon
            prefs[Keys.S_ASSISTANTS] = options.includeAssistants
            prefs[Keys.S_ANESTHESIA] = options.includeAnesthesia
            prefs[Keys.S_DIAGNOSIS] = options.includeDiagnosis
            prefs[Keys.S_PROCEDURE] = options.includeProcedure
            prefs[Keys.S_STEPS] = options.includeOperativeSteps
            prefs[Keys.S_MATERIALS] = options.includeMaterials
            prefs[Keys.S_COMPLICATIONS] = options.includeComplications
            prefs[Keys.S_POSTOP] = options.includePostOp
            prefs[Keys.S_SIGNATURE] = options.includeSignature
            prefs[Keys.S_QR] = options.includeQrCode
        }
    }

    val chartOptionsFlow: Flow<PatientChartPdfOptions> = context.pdfOptionsDataStore.data.map { prefs ->
        PatientChartPdfOptions(
            includeContact = prefs[Keys.P_CONTACT] ?: true,
            includeInsurance = prefs[Keys.P_INSURANCE] ?: true,
            includeMedicalHistory = prefs[Keys.P_MEDICAL_HISTORY] ?: true,
            includeAllergies = prefs[Keys.P_ALLERGIES] ?: true,
            includeChronicDiseases = prefs[Keys.P_CHRONIC] ?: true,
            includePreviousSurgeries = prefs[Keys.P_PREV_SURGERIES] ?: true,
            includeMedications = prefs[Keys.P_MEDICATIONS] ?: true,
            includeNotes = prefs[Keys.P_NOTES] ?: true,
            includeOrthopedicInfo = prefs[Keys.P_ORTHO_INFO] ?: true,
            includeTimeline = prefs[Keys.P_TIMELINE] ?: true,
            includeSurgeries = prefs[Keys.P_SURGERIES] ?: true,
            includeQrCode = prefs[Keys.P_QR] ?: true
        )
    }

    suspend fun saveChartOptions(options: PatientChartPdfOptions) {
        context.pdfOptionsDataStore.edit { prefs ->
            prefs[Keys.P_CONTACT] = options.includeContact
            prefs[Keys.P_INSURANCE] = options.includeInsurance
            prefs[Keys.P_MEDICAL_HISTORY] = options.includeMedicalHistory
            prefs[Keys.P_ALLERGIES] = options.includeAllergies
            prefs[Keys.P_CHRONIC] = options.includeChronicDiseases
            prefs[Keys.P_PREV_SURGERIES] = options.includePreviousSurgeries
            prefs[Keys.P_MEDICATIONS] = options.includeMedications
            prefs[Keys.P_NOTES] = options.includeNotes
            prefs[Keys.P_ORTHO_INFO] = options.includeOrthopedicInfo
            prefs[Keys.P_TIMELINE] = options.includeTimeline
            prefs[Keys.P_SURGERIES] = options.includeSurgeries
            prefs[Keys.P_QR] = options.includeQrCode
        }
    }
}
