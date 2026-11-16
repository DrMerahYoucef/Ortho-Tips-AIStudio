package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * A distinct value the surgeon has previously typed into a "smart" field (e.g. an
 * allergy, a hospital name, an anesthesia type). [useCount] drives ranking so the
 * most habitually-used entries surface first; [fieldKey] namespaces values per form
 * field (see `SuggestionField` in [com.example.data.repository.SuggestionRepository]).
 */
@Entity(
    tableName = "field_suggestions",
    primaryKeys = ["fieldKey", "value"],
    indices = [Index("fieldKey")]
)
data class FieldSuggestionEntity(
    val fieldKey: String,
    val value: String,
    val useCount: Int = 1,
    val lastUsedAt: Long = System.currentTimeMillis()
)
