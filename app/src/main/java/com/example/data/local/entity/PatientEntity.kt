package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single orthopedic patient record. This table is never pre-populated with
 * sample data — rows only ever come from user input or a database import.
 */
@Entity(
    tableName = "patients",
    indices = [
        Index("patientCode", unique = true),
        Index("fullName"),
        Index("diagnosis"),
        Index("bodyRegion"),
        Index("isArchived")
    ]
)
data class PatientEntity(
    @PrimaryKey val id: String,

    // Identification
    val patientCode: String,
    val fullName: String,
    val dateOfBirth: Long? = null, // epoch millis, age is derived at read time
    val gender: String = "",

    // Contact
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val emergencyContactName: String = "",
    val emergencyContactPhone: String = "",

    // Insurance
    val insuranceProvider: String = "",
    val insuranceNumber: String = "",

    // Medical history
    val medicalHistory: String = "",
    val allergies: String = "",
    val chronicDiseases: String = "",
    val previousSurgeries: String = "",
    val medications: String = "",
    val generalNotes: String = "",

    // Orthopedic information
    val diagnosis: String = "",
    val bodyRegion: String = "",
    val side: String = BodySide.NOT_APPLICABLE.name,
    val injuryDate: Long? = null,
    val traumaMechanism: String = "",
    val fractureClassification: String = "",
    val imagingFindings: String = "",
    val treatmentPlan: String = "",

    val isArchived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class BodySide {
    LEFT, RIGHT, BILATERAL, NOT_APPLICABLE
}
