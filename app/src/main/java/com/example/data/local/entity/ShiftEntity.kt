package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A day the surgeon is on call. One row per day, keyed by the date itself so the
 * same day cannot be stored twice.
 */
@Entity(tableName = "shifts")
data class ShiftEntity(
    /** Local date, yyyy-MM-dd. */
    @PrimaryKey val date: String,
    /** "Garde", "Astreinte"… free text, optional. */
    val label: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
