package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "surgeries",
    foreignKeys = [
        ForeignKey(
            entity = PatientEntity::class,
            parentColumns = ["id"],
            childColumns = ["patientId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("patientId"), Index("surgeryDate")]
)
data class SurgeryEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val surgeryDate: Long,
    val hospitalClinic: String = "",
    val surgeon: String = "",
    val assistants: String = "",
    val anesthesiaType: String = "",
    val diagnosis: String = "",
    val procedure: String = "",
    val materialsUsed: String = "",
    val complications: String = "",
    val postOpInstructions: String = "",
    /** Step-by-step operative technique, auto-formatted as bullet points by the editor. */
    val operativeProtocol: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
