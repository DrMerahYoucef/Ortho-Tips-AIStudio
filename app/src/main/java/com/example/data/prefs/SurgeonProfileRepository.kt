package com.example.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.surgeonProfileDataStore by preferencesDataStore(name = "surgeon_profile")

data class SurgeonProfile(
    val name: String = "",
    val specialty: String = "",
    val hospital: String = "",
    val address: String = "",
    val phone: String = "",
    val email: String = "",
    val registrationNumber: String = "",
    /** Absolute path, under app-private storage, to the signature image. */
    val signatureImagePath: String? = null,
    /** Absolute path, under app-private storage, to the clinic logo image. */
    val logoImagePath: String? = null,
    /** Lets the surgeon keep a signature on file but leave it off a given PDF export. */
    val includeSignatureInPdf: Boolean = true
) {
    val isComplete: Boolean
        get() = name.isNotBlank() && hospital.isNotBlank()
}

/**
 * Stores the operating surgeon's identity. Used to auto-fill operative protocols
 * and PDF exports so the surgeon never re-types it per patient.
 */
class SurgeonProfileRepository(private val context: Context) {

    private object Keys {
        val NAME = stringPreferencesKey("surgeon_name")
        val SPECIALTY = stringPreferencesKey("surgeon_specialty")
        val HOSPITAL = stringPreferencesKey("surgeon_hospital")
        val ADDRESS = stringPreferencesKey("surgeon_address")
        val PHONE = stringPreferencesKey("surgeon_phone")
        val EMAIL = stringPreferencesKey("surgeon_email")
        val REGISTRATION_NUMBER = stringPreferencesKey("surgeon_registration_number")
        val SIGNATURE_PATH = stringPreferencesKey("surgeon_signature_path")
        val LOGO_PATH = stringPreferencesKey("surgeon_logo_path")
        val INCLUDE_SIGNATURE_IN_PDF = booleanPreferencesKey("surgeon_include_signature_in_pdf")
    }

    val profileFlow: Flow<SurgeonProfile> = context.surgeonProfileDataStore.data.map { prefs ->
        SurgeonProfile(
            name = prefs[Keys.NAME] ?: "",
            specialty = prefs[Keys.SPECIALTY] ?: "",
            hospital = prefs[Keys.HOSPITAL] ?: "",
            address = prefs[Keys.ADDRESS] ?: "",
            phone = prefs[Keys.PHONE] ?: "",
            email = prefs[Keys.EMAIL] ?: "",
            registrationNumber = prefs[Keys.REGISTRATION_NUMBER] ?: "",
            signatureImagePath = prefs[Keys.SIGNATURE_PATH],
            logoImagePath = prefs[Keys.LOGO_PATH],
            includeSignatureInPdf = prefs[Keys.INCLUDE_SIGNATURE_IN_PDF] ?: true
        )
    }

    suspend fun saveProfile(profile: SurgeonProfile) {
        context.surgeonProfileDataStore.edit { prefs ->
            prefs[Keys.NAME] = profile.name
            prefs[Keys.SPECIALTY] = profile.specialty
            prefs[Keys.HOSPITAL] = profile.hospital
            prefs[Keys.ADDRESS] = profile.address
            prefs[Keys.PHONE] = profile.phone
            prefs[Keys.EMAIL] = profile.email
            prefs[Keys.REGISTRATION_NUMBER] = profile.registrationNumber
            if (profile.signatureImagePath != null) {
                prefs[Keys.SIGNATURE_PATH] = profile.signatureImagePath
            } else {
                prefs.remove(Keys.SIGNATURE_PATH)
            }
            if (profile.logoImagePath != null) {
                prefs[Keys.LOGO_PATH] = profile.logoImagePath
            } else {
                prefs.remove(Keys.LOGO_PATH)
            }
            prefs[Keys.INCLUDE_SIGNATURE_IN_PDF] = profile.includeSignatureInPdf
        }
    }
}
