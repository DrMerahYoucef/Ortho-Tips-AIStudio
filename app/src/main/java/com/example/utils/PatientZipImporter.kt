package com.example.utils

import android.content.Context
import com.example.data.local.entity.ImageCategory
import com.example.data.local.entity.PatientDocumentEntity
import com.example.data.local.entity.PatientEntity
import com.example.data.local.entity.PatientImageEntity
import com.example.data.local.entity.SurgeryEntity
import com.example.data.local.entity.TimelineEventEntity
import com.example.data.repository.PatientRepository
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/** Running tallies shown as the "import/export progress matrix" in the UI. */
data class ImportExportProgress(
    val patientsAdded: Int = 0,
    val patientsSkippedDuplicate: Int = 0,
    val imagesAdded: Int = 0,
    val documentsAdded: Int = 0,
    val errors: List<String> = emptyList(),
    val isComplete: Boolean = false
)

object PatientZipImporter {

    /**
     * Streams the ZIP once: extracts every entry to a scratch dir, parses
     * patients_database.json, then inserts patient-by-patient — skipping any whose
     * patientCode already exists (duplicate detection) — reporting progress after each.
     */
    suspend fun import(
        context: Context,
        repository: PatientRepository,
        zipFile: File,
        onlyPatientIds: Set<String>? = null,
        onProgress: suspend (ImportExportProgress) -> Unit
    ) {
        var progress = ImportExportProgress()
        onProgress(progress)

        val scratchDir = File(context.cacheDir, "patient_import_${System.currentTimeMillis()}")
        scratchDir.mkdirs()

        try {
            ZipInputStream(zipFile.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val outFile = File(scratchDir, entry.name)
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val jsonFile = File(scratchDir, "patients_database.json")
            if (!jsonFile.exists()) {
                progress = progress.copy(errors = progress.errors + "patients_database.json not found in archive", isComplete = true)
                onProgress(progress)
                return
            }

            val root = JSONObject(jsonFile.readText())
            val patientsArray = root.getJSONArray("patients")

            for (i in 0 until patientsArray.length()) {
                val patientJson = patientsArray.getJSONObject(i)
                val sourcePatientId = patientJson.getString("id")

                if (onlyPatientIds != null && sourcePatientId !in onlyPatientIds) continue

                try {
                    val patientCode = patientJson.getString("patientCode")
                    if (!repository.isPatientCodeAvailable(patientCode)) {
                        progress = progress.copy(patientsSkippedDuplicate = progress.patientsSkippedDuplicate + 1)
                        onProgress(progress)
                        continue
                    }

                    // Re-generate all IDs on import so re-importing the same archive twice,
                    // or importing into a database that already has unrelated data, can never collide.
                    val newPatientId = java.util.UUID.randomUUID().toString()

                    val patient = PatientEntity(
                        id = newPatientId,
                        patientCode = patientCode,
                        fullName = patientJson.getString("fullName"),
                        dateOfBirth = patientJson.optLongOrNull("dateOfBirth"),
                        gender = patientJson.optString("gender"),
                        phone = patientJson.optString("phone"),
                        email = patientJson.optString("email"),
                        address = patientJson.optString("address"),
                        emergencyContactName = patientJson.optString("emergencyContactName"),
                        emergencyContactPhone = patientJson.optString("emergencyContactPhone"),
                        insuranceProvider = patientJson.optString("insuranceProvider"),
                        insuranceNumber = patientJson.optString("insuranceNumber"),
                        medicalHistory = patientJson.optString("medicalHistory"),
                        allergies = patientJson.optString("allergies"),
                        chronicDiseases = patientJson.optString("chronicDiseases"),
                        previousSurgeries = patientJson.optString("previousSurgeries"),
                        medications = patientJson.optString("medications"),
                        generalNotes = patientJson.optString("generalNotes"),
                        diagnosis = patientJson.optString("diagnosis"),
                        bodyRegion = patientJson.optString("bodyRegion"),
                        side = patientJson.optString("side", "NOT_APPLICABLE"),
                        injuryDate = patientJson.optLongOrNull("injuryDate"),
                        traumaMechanism = patientJson.optString("traumaMechanism"),
                        fractureClassification = patientJson.optString("fractureClassification"),
                        imagingFindings = patientJson.optString("imagingFindings"),
                        treatmentPlan = patientJson.optString("treatmentPlan"),
                        isArchived = patientJson.optBoolean("isArchived", false),
                        createdAt = patientJson.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = patientJson.optLong("updatedAt", System.currentTimeMillis())
                    )
                    repository.savePatient(patient)
                    progress = progress.copy(patientsAdded = progress.patientsAdded + 1)
                    onProgress(progress)

                    // Timeline events — id remap so surgery/image/document FK references stay consistent.
                    val eventIdMap = mutableMapOf<String, String>()
                    val eventsArray = patientJson.optJSONArray("timelineEvents")
                    if (eventsArray != null) {
                        for (j in 0 until eventsArray.length()) {
                            val eventJson = eventsArray.getJSONObject(j)
                            val newEventId = java.util.UUID.randomUUID().toString()
                            eventIdMap[eventJson.getString("id")] = newEventId
                            repository.saveTimelineEvent(
                                TimelineEventEntity(
                                    id = newEventId,
                                    patientId = newPatientId,
                                    eventDate = eventJson.getLong("eventDate"),
                                    title = eventJson.optString("title"),
                                    description = eventJson.optString("description"),
                                    createdAt = eventJson.optLong("createdAt", System.currentTimeMillis()),
                                    updatedAt = eventJson.optLong("updatedAt", System.currentTimeMillis())
                                )
                            )
                        }
                    }

                    val surgeryIdMap = mutableMapOf<String, String>()
                    val surgeriesArray = patientJson.optJSONArray("surgeries")
                    if (surgeriesArray != null) {
                        for (j in 0 until surgeriesArray.length()) {
                            val surgeryJson = surgeriesArray.getJSONObject(j)
                            val newSurgeryId = java.util.UUID.randomUUID().toString()
                            surgeryIdMap[surgeryJson.getString("id")] = newSurgeryId
                            repository.saveSurgery(
                                SurgeryEntity(
                                    id = newSurgeryId,
                                    patientId = newPatientId,
                                    surgeryDate = surgeryJson.getLong("surgeryDate"),
                                    hospitalClinic = surgeryJson.optString("hospitalClinic"),
                                    surgeon = surgeryJson.optString("surgeon"),
                                    assistants = surgeryJson.optString("assistants"),
                                    anesthesiaType = surgeryJson.optString("anesthesiaType"),
                                    diagnosis = surgeryJson.optString("diagnosis"),
                                    procedure = surgeryJson.optString("procedure"),
                                    materialsUsed = surgeryJson.optString("materialsUsed"),
                                    complications = surgeryJson.optString("complications"),
                                    postOpInstructions = surgeryJson.optString("postOpInstructions"),
                                    createdAt = surgeryJson.optLong("createdAt", System.currentTimeMillis()),
                                    updatedAt = surgeryJson.optLong("updatedAt", System.currentTimeMillis())
                                )
                            )
                        }
                    }

                    val imagesArray = patientJson.optJSONArray("images")
                    if (imagesArray != null) {
                        for (j in 0 until imagesArray.length()) {
                            val imageJson = imagesArray.getJSONObject(j)
                            val archiveFileName = imageJson.optString("archiveFileName")
                            val sourceFile = File(scratchDir, archiveFileName)
                            if (!sourceFile.exists()) {
                                progress = progress.copy(errors = progress.errors + "Missing image file: $archiveFileName")
                                onProgress(progress)
                                continue
                            }
                            val newImageId = java.util.UUID.randomUUID().toString()
                            val isVideo = imageJson.optString("mediaType") ==
                                com.example.data.local.entity.MediaType.VIDEO.name
                            val destFile = if (isVideo) {
                                PatientMediaStorage.newVideoFile(
                                    context, newPatientId, newImageId,
                                    sourceFile.extension.ifBlank { "mp4" }
                                )
                            } else {
                                PatientMediaStorage.newImageFile(context, newPatientId, newImageId)
                            }
                            sourceFile.copyTo(destFile, overwrite = true)

                            var destThumbPath: String? = null
                            if (imageJson.optBoolean("hasThumbnail", false)) {
                                val thumbSource = File(scratchDir, "images/thumbs/${imageJson.getString("id")}.jpg")
                                if (thumbSource.exists()) {
                                    val destThumb = PatientMediaStorage.newThumbnailFile(context, newPatientId, newImageId)
                                    thumbSource.copyTo(destThumb, overwrite = true)
                                    destThumbPath = destThumb.absolutePath
                                }
                            }

                            repository.saveImage(
                                PatientImageEntity(
                                    id = newImageId,
                                    patientId = newPatientId,
                                    timelineEventId = eventIdMap[imageJson.optString("timelineEventId", null)],
                                    surgeryId = surgeryIdMap[imageJson.optString("surgeryId", null)],
                                    category = imageJson.optString("category", ImageCategory.OTHER.name),
                                    filePath = destFile.absolutePath,
                                    thumbnailPath = destThumbPath,
                                    ocrText = imageJson.optString("ocrText"),
                                    annotationsJson = imageJson.optString("annotationsJson"),
                                    notes = imageJson.optString("notes"),
                                    mediaType = imageJson.optString(
                                        "mediaType",
                                        com.example.data.local.entity.MediaType.IMAGE.name
                                    ),
                                    durationMs = imageJson.optLong("durationMs", 0L),
                                    capturedAt = imageJson.optLong("capturedAt", System.currentTimeMillis())
                                )
                            )
                            progress = progress.copy(imagesAdded = progress.imagesAdded + 1)
                            onProgress(progress)
                        }
                    }

                    val documentsArray = patientJson.optJSONArray("documents")
                    if (documentsArray != null) {
                        for (j in 0 until documentsArray.length()) {
                            val docJson = documentsArray.getJSONObject(j)
                            val archiveFileName = docJson.optString("archiveFileName")
                            val sourceFile = File(scratchDir, archiveFileName)
                            if (!sourceFile.exists()) {
                                progress = progress.copy(errors = progress.errors + "Missing document file: $archiveFileName")
                                onProgress(progress)
                                continue
                            }
                            val newDocId = java.util.UUID.randomUUID().toString()
                            val destFile = PatientMediaStorage.newDocumentFile(
                                context, newPatientId, newDocId, sourceFile.extension.ifBlank { "pdf" }
                            )
                            sourceFile.copyTo(destFile, overwrite = true)

                            repository.saveDocument(
                                PatientDocumentEntity(
                                    id = newDocId,
                                    patientId = newPatientId,
                                    timelineEventId = eventIdMap[docJson.optString("timelineEventId", null)],
                                    surgeryId = surgeryIdMap[docJson.optString("surgeryId", null)],
                                    title = docJson.optString("title"),
                                    filePath = destFile.absolutePath,
                                    mimeType = docJson.optString("mimeType", "application/pdf"),
                                    ocrText = docJson.optString("ocrText"),
                                    createdAt = docJson.optLong("createdAt", System.currentTimeMillis())
                                )
                            )
                            progress = progress.copy(documentsAdded = progress.documentsAdded + 1)
                            onProgress(progress)
                        }
                    }
                } catch (e: Exception) {
                    progress = progress.copy(errors = progress.errors + "Patient ${i + 1}: ${e.message}")
                    onProgress(progress)
                }
            }
        } finally {
            scratchDir.deleteRecursively()
            progress = progress.copy(isComplete = true)
            onProgress(progress)
        }
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null
}
