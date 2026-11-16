package com.example.utils

import android.content.Context
import com.example.data.local.entity.PatientDocumentEntity
import com.example.data.local.entity.PatientEntity
import com.example.data.local.entity.PatientImageEntity
import com.example.data.local.entity.SurgeryEntity
import com.example.data.local.entity.TimelineEventEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One patient's full record bundle, gathered before zipping. */
data class PatientExportBundle(
    val patient: PatientEntity,
    val timelineEvents: List<TimelineEventEntity>,
    val surgeries: List<SurgeryEntity>,
    val images: List<PatientImageEntity>,
    val documents: List<PatientDocumentEntity>
)

/**
 * Produces a ZIP with:
 *   patients_database.json
 *   /images/<imageId>.jpg
 *   /images/thumbs/<imageId>.jpg
 *   /documents/<documentId>.<ext>
 *
 * The JSON keeps every entity field so import can reconstruct rows exactly, plus each
 * media entity carries the archive-relative file name it was written under.
 */
object PatientZipExporter {

    fun export(context: Context, bundles: List<PatientExportBundle>, destination: File) {
        ZipOutputStream(FileOutputStream(destination)).use { zip ->
            val patientsJson = JSONArray()

            bundles.forEach { bundle ->
                val patientJson = JSONObject().apply {
                    put("id", bundle.patient.id)
                    put("patientCode", bundle.patient.patientCode)
                    put("fullName", bundle.patient.fullName)
                    putOpt("dateOfBirth", bundle.patient.dateOfBirth)
                    put("gender", bundle.patient.gender)
                    put("phone", bundle.patient.phone)
                    put("email", bundle.patient.email)
                    put("address", bundle.patient.address)
                    put("emergencyContactName", bundle.patient.emergencyContactName)
                    put("emergencyContactPhone", bundle.patient.emergencyContactPhone)
                    put("insuranceProvider", bundle.patient.insuranceProvider)
                    put("insuranceNumber", bundle.patient.insuranceNumber)
                    put("medicalHistory", bundle.patient.medicalHistory)
                    put("allergies", bundle.patient.allergies)
                    put("chronicDiseases", bundle.patient.chronicDiseases)
                    put("previousSurgeries", bundle.patient.previousSurgeries)
                    put("medications", bundle.patient.medications)
                    put("generalNotes", bundle.patient.generalNotes)
                    put("diagnosis", bundle.patient.diagnosis)
                    put("bodyRegion", bundle.patient.bodyRegion)
                    put("side", bundle.patient.side)
                    putOpt("injuryDate", bundle.patient.injuryDate)
                    put("traumaMechanism", bundle.patient.traumaMechanism)
                    put("fractureClassification", bundle.patient.fractureClassification)
                    put("imagingFindings", bundle.patient.imagingFindings)
                    put("treatmentPlan", bundle.patient.treatmentPlan)
                    put("isArchived", bundle.patient.isArchived)
                    put("createdAt", bundle.patient.createdAt)
                    put("updatedAt", bundle.patient.updatedAt)

                    put(
                        "timelineEvents",
                        JSONArray(
                            bundle.timelineEvents.map { event ->
                                JSONObject().apply {
                                    put("id", event.id)
                                    put("eventDate", event.eventDate)
                                    put("title", event.title)
                                    put("description", event.description)
                                    put("createdAt", event.createdAt)
                                    put("updatedAt", event.updatedAt)
                                }
                            }
                        )
                    )

                    put(
                        "surgeries",
                        JSONArray(
                            bundle.surgeries.map { surgery ->
                                JSONObject().apply {
                                    put("id", surgery.id)
                                    put("surgeryDate", surgery.surgeryDate)
                                    put("hospitalClinic", surgery.hospitalClinic)
                                    put("surgeon", surgery.surgeon)
                                    put("assistants", surgery.assistants)
                                    put("anesthesiaType", surgery.anesthesiaType)
                                    put("diagnosis", surgery.diagnosis)
                                    put("procedure", surgery.procedure)
                                    put("materialsUsed", surgery.materialsUsed)
                                    put("complications", surgery.complications)
                                    put("postOpInstructions", surgery.postOpInstructions)
                                    put("createdAt", surgery.createdAt)
                                    put("updatedAt", surgery.updatedAt)
                                }
                            }
                        )
                    )

                    put(
                        "images",
                        JSONArray(
                            bundle.images.mapNotNull { image ->
                                val sourceFile = File(image.filePath)
                                if (!sourceFile.exists()) return@mapNotNull null
                                // Videos keep their own extension so players still open them.
                                val extension = sourceFile.extension.ifBlank { "jpg" }
                                val entryName = "images/${image.id}.$extension"
                                zip.writeFile(sourceFile, entryName)
                                image.thumbnailPath?.let { thumbPath ->
                                    val thumbFile = File(thumbPath)
                                    if (thumbFile.exists()) zip.writeFile(thumbFile, "images/thumbs/${image.id}.jpg")
                                }
                                JSONObject().apply {
                                    put("id", image.id)
                                    putOpt("timelineEventId", image.timelineEventId)
                                    putOpt("surgeryId", image.surgeryId)
                                    put("category", image.category)
                                    put("archiveFileName", entryName)
                                    put("hasThumbnail", image.thumbnailPath != null)
                                    put("ocrText", image.ocrText)
                                    put("annotationsJson", image.annotationsJson)
                                    put("notes", image.notes)
                                    put("capturedAt", image.capturedAt)
                                    put("mediaType", image.mediaType)
                                    put("durationMs", image.durationMs)
                                }
                            }
                        )
                    )

                    put(
                        "documents",
                        JSONArray(
                            bundle.documents.mapNotNull { doc ->
                                val sourceFile = File(doc.filePath)
                                if (!sourceFile.exists()) return@mapNotNull null
                                val extension = sourceFile.extension.ifBlank { "pdf" }
                                val entryName = "documents/${doc.id}.$extension"
                                zip.writeFile(sourceFile, entryName)
                                JSONObject().apply {
                                    put("id", doc.id)
                                    putOpt("timelineEventId", doc.timelineEventId)
                                    putOpt("surgeryId", doc.surgeryId)
                                    put("title", doc.title)
                                    put("archiveFileName", entryName)
                                    put("mimeType", doc.mimeType)
                                    put("ocrText", doc.ocrText)
                                    put("createdAt", doc.createdAt)
                                }
                            }
                        )
                    )
                }
                patientsJson.put(patientJson)
            }

            val root = JSONObject().apply {
                put("exportedAt", System.currentTimeMillis())
                put("patients", patientsJson)
            }

            zip.putNextEntry(ZipEntry("patients_database.json"))
            zip.write(root.toString(2).toByteArray())
            zip.closeEntry()
        }
    }

    private fun ZipOutputStream.writeFile(file: File, entryName: String) {
        putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }
}
