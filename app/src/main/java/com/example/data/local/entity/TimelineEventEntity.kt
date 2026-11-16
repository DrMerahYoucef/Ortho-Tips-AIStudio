package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single timeline entry for a patient (consultation, imaging, follow-up, planned
 * procedure, etc). Whether an event reads as "previous", "current" or "future" is
 * derived from [eventDate] relative to now at query time, so the bucket a row falls
 * into is never stale.
 */
@Entity(
    tableName = "timeline_events",
    foreignKeys = [
        ForeignKey(
            entity = PatientEntity::class,
            parentColumns = ["id"],
            childColumns = ["patientId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("patientId"), Index("eventDate")]
)
data class TimelineEventEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val eventDate: Long,
    val title: String,
    val description: String = "",
    /** [Attendance] name: whether the patient turned up for this appointment. */
    val attendance: String = Attendance.UNKNOWN.name,
    /** Short note added from the widget or the form. */
    val remark: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** Did the patient come to the appointment? */
enum class Attendance { UNKNOWN, ATTENDED, MISSED }
