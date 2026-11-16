package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "patient_documents",
    foreignKeys = [
        ForeignKey(
            entity = PatientEntity::class,
            parentColumns = ["id"],
            childColumns = ["patientId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TimelineEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["timelineEventId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = SurgeryEntity::class,
            parentColumns = ["id"],
            childColumns = ["surgeryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("patientId"),
        Index("timelineEventId"),
        Index("surgeryId")
    ]
)
data class PatientDocumentEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val timelineEventId: String? = null,
    val surgeryId: String? = null,
    val title: String,
    val filePath: String,
    val mimeType: String = "application/pdf",
    val ocrText: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
