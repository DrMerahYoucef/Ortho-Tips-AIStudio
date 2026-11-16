package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Learned association between two field values within one record.
 *
 * This is where the "smart" in smart input actually lives. Knowing that this
 * surgeon's distal radius fractures are almost always followed by a volar plate
 * ORIF is worth far more than knowing that "ORIF" is a frequent word overall,
 * and it is the difference between an autocomplete and a useful prediction.
 *
 * Added in schema v6, additive only.
 */
@Entity(
    tableName = "field_pairs",
    primaryKeys = ["sourceField", "sourceValue", "targetField", "targetValue"],
    indices = [Index("sourceField", "sourceValue", "targetField")]
)
data class FieldPairEntity(
    val sourceField: String,
    /** Normalised, so casing and accents do not fragment the association. */
    val sourceValue: String,
    val targetField: String,
    /** Stored as typed, since this is what gets offered back to the surgeon. */
    val targetValue: String,
    val count: Int,
    val lastUsedAt: Long
)
