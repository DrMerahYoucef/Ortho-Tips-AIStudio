package com.example.data.repository

import com.example.data.local.OrthoDatabase
import com.example.data.local.entity.FieldPairEntity
import com.example.data.local.entity.FieldSuggestionEntity
import com.example.data.prefs.SettingsRepository
import com.example.data.suggestion.SuggestionCatalog
import com.example.data.suggestion.SuggestionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Namespaced keys for every "smart" field across the patient & surgery forms. */
object SuggestionField {
    // Patient — medical history
    const val MEDICAL_HISTORY = "patient.medicalHistory"
    const val ALLERGIES = "patient.allergies"
    const val CHRONIC_DISEASES = "patient.chronicDiseases"
    const val PREVIOUS_SURGERIES = "patient.previousSurgeries"
    const val MEDICATIONS = "patient.medications"

    // Patient — orthopedic information
    const val DIAGNOSIS = "patient.diagnosis"
    const val BODY_REGION = "patient.bodyRegion"
    const val TRAUMA_MECHANISM = "patient.traumaMechanism"
    const val FRACTURE_CLASSIFICATION = "patient.fractureClassification"
    const val IMAGING_FINDINGS = "patient.imagingFindings"
    const val TREATMENT_PLAN = "patient.treatmentPlan"

    // Surgery record
    const val SURGERY_HOSPITAL = "surgery.hospital"
    const val SURGERY_SURGEON = "surgery.surgeon"
    const val SURGERY_ASSISTANTS = "surgery.assistants"
    const val SURGERY_ANESTHESIA = "surgery.anesthesia"
    const val SURGERY_DIAGNOSIS = "surgery.diagnosis"
    const val SURGERY_PROCEDURE = "surgery.procedure"
    const val SURGERY_MATERIALS = "surgery.materials"
    const val SURGERY_COMPLICATIONS = "surgery.complications"
    const val SURGERY_POSTOP = "surgery.postOp"
    const val SURGERY_PROTOCOL = "surgery.protocol"
}

/**
 * Backs the smart-input fields: what the surgeon has typed before, plus a small
 * shipped vocabulary, plus what tends to follow what.
 *
 * Three behaviours distinguish this from a plain autocomplete:
 *
 *  1. **Seeded.** A brand-new install already offers the common anaesthesia
 *     types and body regions, so the first case is not typed from nothing. Seeds
 *     score below anything the surgeon has actually used, so they fade as real
 *     vocabulary accumulates.
 *  2. **Field-aware learning.** A list field learns its items; a prose field
 *     learns the whole entry, and only when short enough to be reusable. The old
 *     blanket comma-split turned treatment plans into sentence fragments.
 *  3. **Associations.** Saving a record records which values appeared together,
 *     so the procedure field can lead with what actually followed this diagnosis
 *     last time.
 *
 * The on/off toggle still governs both suggesting and learning.
 */
class SuggestionRepository(
    private val database: OrthoDatabase,
    private val settingsRepository: SettingsRepository
) {
    private val dao = database.suggestionDao()

    val suggestionsEnabled: Flow<Boolean> =
        settingsRepository.settingsFlow.map { it.smartSuggestionsEnabled }

    /**
     * Ranked vocabulary for a field, most useful first.
     *
     * Returns values rather than scores so existing callers are unchanged; the
     * field component re-ranks against the live query as the surgeon types.
     */
    fun suggestionsFor(fieldKey: String): Flow<List<String>> =
        combine(dao.getAllForField(fieldKey), suggestionsEnabled) { learned, enabled ->
            if (!enabled) return@combine emptyList()
            val now = System.currentTimeMillis()
            val learnedValues = learned.map { it.value }
            val learnedNormalized = learnedValues.map(SuggestionEngine::normalize).toSet()

            val candidates = learned.map {
                SuggestionEngine.Candidate(it.value, it.useCount, it.lastUsedAt)
            } + SuggestionCatalog.seedsFor(fieldKey)
                // A seed the surgeon has already typed is now their own value;
                // keeping both would show the same term twice.
                .filterNot { SuggestionEngine.normalize(it) in learnedNormalized }
                .map { SuggestionEngine.Candidate(it, useCount = 0, lastUsedAt = 0, isSeed = true) }

            candidates.sortedByDescending { SuggestionEngine.familiarity(it, now) }
                .map { it.value }
        }.flowOn(Dispatchers.IO)

    /**
     * Values that have followed [sourceValue] in the predicting field before.
     *
     * Ordered by how often, so the field can surface "what you usually do next"
     * ahead of general vocabulary.
     */
    suspend fun predictionsFor(
        fieldKey: String,
        sourceValue: String
    ): List<String> = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext emptyList()
        val sourceField = SuggestionCatalog.predictorFor(fieldKey) ?: return@withContext emptyList()
        val normalized = SuggestionEngine.normalize(sourceValue)
        if (normalized.isEmpty()) return@withContext emptyList()
        dao.getPairs(sourceField, normalized, fieldKey).map { it.targetValue }
    }

    suspend fun setEnabled(enabled: Boolean) {
        settingsRepository.setSmartSuggestions(enabled)
    }

    /**
     * Learns the values in [rawValue] for [fieldKey], split according to that
     * field's kind. No-ops when the toggle is off.
     */
    suspend fun learn(fieldKey: String, rawValue: String) = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext
        SuggestionCatalog.itemsToLearn(fieldKey, rawValue).forEach { item ->
            val existingCount = dao.getUseCount(fieldKey, item) ?: 0
            dao.upsert(
                FieldSuggestionEntity(
                    fieldKey = fieldKey,
                    value = item,
                    useCount = existingCount + 1,
                    lastUsedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Learns a whole form at once: every field's values, and the associations
     * between them.
     *
     * Callers pass the form as it was saved. Doing it in one place means the
     * association graph cannot drift out of step with what was learned, which it
     * would if each field recorded its own pairs.
     */
    suspend fun learnRecord(values: Map<String, String>) = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext
        values.forEach { (fieldKey, raw) -> learn(fieldKey, raw) }

        val now = System.currentTimeMillis()
        values.forEach { (targetField, rawTarget) ->
            val sourceField = SuggestionCatalog.predictorFor(targetField) ?: return@forEach
            val sourceValue = values[sourceField]?.let(SuggestionEngine::canonicalize)
                ?: return@forEach
            val targetValue = SuggestionCatalog.itemsToLearn(targetField, rawTarget)
                .firstOrNull() ?: return@forEach
            val normalizedSource = SuggestionEngine.normalize(sourceValue)
            val existing = dao.getPairCount(
                sourceField, normalizedSource, targetField, targetValue
            ) ?: 0
            dao.upsertPair(
                FieldPairEntity(
                    sourceField = sourceField,
                    sourceValue = normalizedSource,
                    targetField = targetField,
                    targetValue = targetValue,
                    count = existing + 1,
                    lastUsedAt = now
                )
            )
        }
    }

    /** Removes one learned value. Used by the long-press "forget" in the field. */
    suspend fun forget(fieldKey: String, value: String) = withContext(Dispatchers.IO) {
        dao.delete(fieldKey, value)
    }

    private suspend fun enabled(): Boolean =
        settingsRepository.settingsFlow.first().smartSuggestionsEnabled
}
