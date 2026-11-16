package com.example.ui.components

import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.data.local.ContentLocale

/**
 * Some patient fields store a fixed word (side enum, gender, a body region picked
 * from the suggestions). The stored word stays as it is; these helpers only show
 * it in the current app language.
 */
object PatientValueLabels {

    // English ↔ French pairs for the body regions offered as suggestions.
    private val regions = listOf(
        "Shoulder" to "Épaule", "Humerus" to "Humérus", "Elbow" to "Coude",
        "Forearm" to "Avant-bras", "Wrist" to "Poignet", "Hand" to "Main",
        "Hip" to "Hanche", "Femur" to "Fémur", "Knee" to "Genou",
        "Tibia" to "Tibia", "Ankle" to "Cheville", "Foot" to "Pied",
        "Cervical spine" to "Rachis cervical", "Thoracic spine" to "Rachis thoracique",
        "Lumbar spine" to "Rachis lombaire", "Pelvis" to "Bassin"
    )

    fun bodyRegion(value: String): String {
        val key = value.trim()
        val pair = regions.firstOrNull {
            it.first.equals(key, ignoreCase = true) || it.second.equals(key, ignoreCase = true)
        } ?: return value
        return if (ContentLocale.isFrench) pair.second else pair.first
    }

    @Composable
    fun side(value: String): String = when (value.trim().uppercase()) {
        "LEFT" -> stringResource(R.string.patient_edit_side_left)
        "RIGHT" -> stringResource(R.string.patient_edit_side_right)
        "BILATERAL" -> stringResource(R.string.patient_edit_side_bilateral)
        "NOT_APPLICABLE" -> stringResource(R.string.patient_edit_side_not_applicable)
        else -> value
    }

    @Composable
    fun gender(value: String): String = when (value.trim().lowercase()) {
        "male", "homme" -> stringResource(R.string.patient_edit_gender_male)
        "female", "femme" -> stringResource(R.string.patient_edit_gender_female)
        "other", "autre" -> stringResource(R.string.patient_edit_gender_other)
        else -> value
    }

    /** "%d item(s)"-style text; the count is also the format argument. */
    @Composable
    fun plural(@PluralsRes id: Int, count: Int): String =
        LocalContext.current.resources.getQuantityString(id, count, count)
}
