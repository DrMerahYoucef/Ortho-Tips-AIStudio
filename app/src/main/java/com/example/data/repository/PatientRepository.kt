package com.example.data.repository

import com.example.data.local.OrthoDatabase
import com.example.data.local.entity.PatientDocumentEntity
import com.example.data.local.entity.PatientEntity
import com.example.data.local.entity.PatientImageEntity
import com.example.data.local.entity.SurgeryEntity
import com.example.data.local.entity.TimelineEventEntity
import com.example.data.local.model.PatientDetail
import com.example.data.local.model.PatientSearchMatchType
import com.example.data.local.model.PatientSearchResult
import com.example.data.local.model.PatientSummary
import com.example.data.local.model.PatientTimeline
import com.example.data.local.model.calculateAge
import com.example.data.local.model.toSummary
import com.example.data.storage.DriveSpaceSummary
import com.example.data.storage.OffloadResult
import com.example.data.storage.PatientDriveStatus
import com.example.data.storage.PatientMediaGateway
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [patientMediaDir] is the app-private root where captured/imported patient images and
 * documents are written (see [com.example.utils.PatientMediaStorage]). Optional so the
 * repository stays unit-testable without a real filesystem.
 *
 * [mediaGateway] routes media through the storage abstraction: saved files are queued
 * for the active backend and deletions become recoverable soft deletes that propagate.
 * Also optional, and null in tests — when it is absent the repository behaves exactly
 * as it did before the Telegram Drive backend existed, including hard-deleting files.
 */
class PatientRepository(
    private val database: OrthoDatabase,
    private val patientMediaDir: File? = null,
    private val mediaGateway: PatientMediaGateway? = null,
    /** Only used to keep the home-screen widget in step with the timeline. */
    private val appContext: android.content.Context? = null
) {

    private fun refreshWidget() {
        appContext?.let { com.example.widget.OrthoWidgetProvider.refreshAll(it) }
    }

    private val patientDao = database.patientDao()
    private val timelineDao = database.timelineEventDao()
    private val surgeryDao = database.surgeryDao()
    private val imageDao = database.patientImageDao()
    private val documentDao = database.patientDocumentDao()

    // ---- Patients ----------------------------------------------------------------

    fun getActivePatientSummaries(): Flow<List<PatientSummary>> =
        patientDao.getActivePatients().map { list -> list.map { it.toSummary() } }.flowOn(Dispatchers.IO)

    fun getArchivedPatientSummaries(): Flow<List<PatientSummary>> =
        patientDao.getArchivedPatients().map { list -> list.map { it.toSummary() } }.flowOn(Dispatchers.IO)

    fun searchPatientSummaries(query: String): Flow<List<PatientSummary>> =
        patientDao.searchPatients(query.trim()).map { list -> list.map { it.toSummary() } }.flowOn(Dispatchers.IO)

    suspend fun getPatient(id: String): PatientEntity? = withContext(Dispatchers.IO) {
        patientDao.getPatientDirect(id)
    }

    /**
     * Full profile screen data: patient fields, bucketed timeline, surgeries and all
     * attached media, combined into one stream so the UI recomposes as any part changes.
     */
    fun getPatientDetail(patientId: String): Flow<PatientDetail?> {
        return combine(
            patientDao.getPatientById(patientId),
            timelineDao.getEventsForPatient(patientId),
            surgeryDao.getSurgeriesForPatient(patientId),
            imageDao.getImagesForPatient(patientId),
            documentDao.getDocumentsForPatient(patientId)
        ) { patient, events, surgeries, images, documents ->
            if (patient == null) null
            else PatientDetail(
                patient = patient,
                age = calculateAge(patient.dateOfBirth),
                timeline = PatientTimeline.bucket(events),
                surgeries = surgeries,
                images = images,
                documents = documents
            )
        }.flowOn(Dispatchers.IO)
    }

    /** Validates the human-readable patient ID is unique before create/update. */
    suspend fun isPatientCodeAvailable(patientCode: String, excludePatientId: String = ""): Boolean =
        withContext(Dispatchers.IO) {
            !patientDao.isPatientCodeTaken(patientCode.trim(), excludePatientId)
        }

    suspend fun savePatient(patient: PatientEntity) = withContext(Dispatchers.IO) {
        val row = patient.copy(updatedAt = System.currentTimeMillis())
        // Update first. Inserting with REPLACE would delete the existing row and
        // re-create it, and the cascade would take the timeline, surgeries, media
        // and documents with it — editing a patient used to empty their record.
        if (patientDao.updatePatient(row) == 0) {
            patientDao.insertPatient(row)
        }
        mediaGateway?.onRecordsChanged()
    }

    suspend fun setArchived(patientId: String, archived: Boolean) = withContext(Dispatchers.IO) {
        patientDao.setArchived(patientId, archived)
        mediaGateway?.onRecordsChanged()
    }

    /** Deletes the patient row (cascades to timeline/surgeries/images/documents) and their files on disk. */
    suspend fun deletePatient(patientId: String) = withContext(Dispatchers.IO) {
        val images = imageDao.getImagesForPatientDirect(patientId)
        val documents = documentDao.getDocumentsForPatientDirect(patientId)
        patientDao.deletePatient(patientId)
        deleteMediaFiles(images, documents)
        mediaGateway?.onRecordsChanged()
    }

    /** Deletes every patient, their timeline, surgeries, media and documents. */
    suspend fun deleteAllPatients() = withContext(Dispatchers.IO) {
        val ids = patientDao.getAllPatientIds()
        deletePatients(ids)
    }

    suspend fun deletePatients(patientIds: List<String>) = withContext(Dispatchers.IO) {
        patientIds.forEach { id ->
            val images = imageDao.getImagesForPatientDirect(id)
            val documents = documentDao.getDocumentsForPatientDirect(id)
            deleteMediaFiles(images, documents)
        }
        patientDao.deletePatients(patientIds)
        mediaGateway?.onRecordsChanged()
    }

    /**
     * With a gateway present this is a soft delete: files move to the recoverable
     * trash and a tombstone is recorded so the removal reaches the drive. Without
     * one it is the original unlink, so nothing changes for an install that has
     * never enabled a cloud backend.
     */
    private suspend fun deleteMediaFiles(images: List<PatientImageEntity>, documents: List<PatientDocumentEntity>) {
        val gateway = mediaGateway
        if (gateway != null) {
            gateway.onPatientMediaDeleted(images, documents)
            return
        }
        images.forEach { img ->
            File(img.filePath).delete()
            img.thumbnailPath?.let { File(it).delete() }
        }
        documents.forEach { doc -> File(doc.filePath).delete() }
    }

    // ---- Timeline ------------------------------------------------------------------

    fun getTimelineForPatient(patientId: String): Flow<PatientTimeline> =
        timelineDao.getEventsForPatient(patientId).map { PatientTimeline.bucket(it) }.flowOn(Dispatchers.IO)

    suspend fun getTimelineEvent(eventId: String): TimelineEventEntity? = withContext(Dispatchers.IO) {
        timelineDao.getEventDirect(eventId)
    }

    suspend fun saveTimelineEvent(event: TimelineEventEntity) = withContext(Dispatchers.IO) {
        timelineDao.insertEvent(event.copy(updatedAt = System.currentTimeMillis()))
        patientDao.touchUpdatedAt(event.patientId)
        mediaGateway?.onRecordsChanged()
        refreshWidget()
    }

    suspend fun setAttendance(eventId: String, attendance: com.example.data.local.entity.Attendance) =
        withContext(Dispatchers.IO) {
            val event = timelineDao.getEventDirect(eventId)
            timelineDao.setAttendance(eventId, attendance.name)
            event?.patientId?.let { patientDao.touchUpdatedAt(it) }
            mediaGateway?.onRecordsChanged()
            refreshWidget()
        }

    suspend fun deleteTimelineEvent(eventId: String) = withContext(Dispatchers.IO) {
        val event = timelineDao.getEventDirect(eventId)
        timelineDao.deleteEvent(eventId)
        event?.patientId?.let { patientDao.touchUpdatedAt(it) }
        mediaGateway?.onRecordsChanged()
        refreshWidget()
    }

    // ---- Surgeries -------------------------------------------------------------------

    fun getSurgeriesForPatient(patientId: String): Flow<List<SurgeryEntity>> =
        surgeryDao.getSurgeriesForPatient(patientId).flowOn(Dispatchers.IO)

    suspend fun getSurgery(surgeryId: String): SurgeryEntity? = withContext(Dispatchers.IO) {
        surgeryDao.getSurgeryDirect(surgeryId)
    }

    suspend fun saveSurgery(surgery: SurgeryEntity) = withContext(Dispatchers.IO) {
        surgeryDao.insertSurgery(surgery.copy(updatedAt = System.currentTimeMillis()))
        patientDao.touchUpdatedAt(surgery.patientId)
        mediaGateway?.onRecordsChanged()
    }

    suspend fun deleteSurgery(surgeryId: String) = withContext(Dispatchers.IO) {
        val surgery = surgeryDao.getSurgeryDirect(surgeryId)
        surgeryDao.deleteSurgery(surgeryId)
        surgery?.patientId?.let { patientDao.touchUpdatedAt(it) }
        mediaGateway?.onRecordsChanged()
    }

    // ---- Images & documents ----------------------------------------------------------

    fun getImagesForPatient(patientId: String): Flow<List<PatientImageEntity>> =
        imageDao.getImagesForPatient(patientId).flowOn(Dispatchers.IO)

    suspend fun getImageOrNull(imageId: String): PatientImageEntity? = withContext(Dispatchers.IO) {
        imageDao.getImageDirect(imageId)
    }

    fun getImagesForEvent(eventId: String): Flow<List<PatientImageEntity>> =
        imageDao.getImagesForEvent(eventId).flowOn(Dispatchers.IO)

    fun getImagesForSurgery(surgeryId: String): Flow<List<PatientImageEntity>> =
        imageDao.getImagesForSurgery(surgeryId).flowOn(Dispatchers.IO)

    suspend fun saveImage(image: PatientImageEntity) = withContext(Dispatchers.IO) {
        imageDao.insertImage(image)
        patientDao.touchUpdatedAt(image.patientId)
        // Queued after the row is committed, so a transfer can never reference an
        // image the database does not yet know about.
        mediaGateway?.onImageSaved(image)
    }

    suspend fun deleteImage(imageId: String) = withContext(Dispatchers.IO) {
        val image = imageDao.getImageDirect(imageId)
        imageDao.deleteImage(imageId)
        image?.let {
            patientDao.touchUpdatedAt(it.patientId)
            val gateway = mediaGateway
            if (gateway != null) {
                gateway.onImageDeleted(it)
                gateway.onRecordsChanged()
            } else {
                File(it.filePath).delete()
                it.thumbnailPath?.let { path -> File(path).delete() }
            }
        }
    }

    /**
     * Returns a readable file for a stored image, fetching it from the active
     * backend when the local copy is gone. Callers that need the bytes rather
     * than a path — the PDF exporter, the annotation canvas, the share sheet —
     * should go through these instead of constructing a `File` from `filePath`.
     */
    suspend fun resolveImageFile(image: PatientImageEntity): File? =
        mediaGateway?.resolve(image.filePath) ?: File(image.filePath).takeIf { it.exists() }

    suspend fun resolveThumbnailFile(image: PatientImageEntity): File? =
        mediaGateway?.resolve(image.thumbnailPath)
            ?: image.thumbnailPath?.let { File(it) }?.takeIf { it.exists() }

    suspend fun resolveDocumentFile(document: PatientDocumentEntity): File? =
        mediaGateway?.resolve(document.filePath) ?: File(document.filePath).takeIf { it.exists() }

    /**
     * How much of this patient is safely in the drive.
     *
     * Thumbnails are counted as media but never offloaded — they are what keeps
     * the gallery usable after the full-size files are gone, and they cost
     * kilobytes against the megabytes a radiograph takes.
     */
    suspend fun driveStatus(patientId: String): PatientDriveStatus {
        val gateway = mediaGateway
        val images = imageDao.getImagesForPatientDirect(patientId)
        val documents = documentDao.getDocumentsForPatientDirect(patientId)
        val fullSize = images.map { it.filePath } + documents.map { it.filePath }
        val thumbnails = images.mapNotNull { it.thumbnailPath }

        if (gateway == null) {
            return PatientDriveStatus(
                patientId = patientId,
                driveEnabled = false,
                totalMedia = fullSize.size,
                backedUpMedia = 0,
                recordsBackedUp = false,
                offloadableBytes = 0,
                localBytes = 0
            )
        }

        var backedUp = 0
        var offloadable = 0L
        var local = 0L
        for (path in fullSize) {
            local += gateway.localBytes(path)
            if (gateway.isBackedUp(path)) {
                backedUp++
                offloadable += gateway.localBytes(path)
            }
        }
        thumbnails.forEach { local += gateway.localBytes(it) }

        return PatientDriveStatus(
            patientId = patientId,
            driveEnabled = gateway.isDriveEnabled(),
            totalMedia = fullSize.size,
            backedUpMedia = backedUp,
            // Records ride in the whole-database snapshot, so a patient's fields
            // are safe once a snapshot newer than their last edit is in the drive.
            recordsBackedUp = gateway.recordsBackedUpSince(
                patientDao.getPatientDirect(patientId)?.updatedAt ?: 0
            ),
            offloadableBytes = offloadable,
            localBytes = local
        )
    }

    /** Reclaimable local space across every patient. Zero when the drive is off. */
    suspend fun reclaimableSpace(): DriveSpaceSummary =
        mediaGateway?.offloadableSummary() ?: DriveSpaceSummary()

    /** Frees every verified-backed-up file in the library. */
    suspend fun offloadAllPatients(): OffloadResult =
        mediaGateway?.offloadAll() ?: OffloadResult(0, 0, 0)

    /**
     * Frees local space for one patient, keeping records and thumbnails.
     * Never touches the drive copy and never deletes a file that is not verified
     * as backed up.
     */
    suspend fun offloadPatientMedia(patientId: String): OffloadResult {
        val gateway = mediaGateway ?: return OffloadResult(0, 0, 0)
        val images = imageDao.getImagesForPatientDirect(patientId)
        val documents = documentDao.getDocumentsForPatientDirect(patientId)
        return gateway.offload(images.map { it.filePath } + documents.map { it.filePath })
    }

    /**
     * Warms the cache for a patient's media. Called when a record is opened so
     * scrolling their imaging does not stall on a round trip per image.
     */
    suspend fun prefetchPatientMedia(patientId: String): Int {
        val gateway = mediaGateway ?: return 0
        val images = imageDao.getImagesForPatientDirect(patientId)
        val documents = documentDao.getDocumentsForPatientDirect(patientId)
        return gateway.prefetch(
            images.map { it.thumbnailPath } + images.map { it.filePath } +
                    documents.map { it.filePath }
        )
    }

    fun getDocumentsForPatient(patientId: String): Flow<List<PatientDocumentEntity>> =
        documentDao.getDocumentsForPatient(patientId).flowOn(Dispatchers.IO)

    suspend fun saveDocument(document: PatientDocumentEntity) = withContext(Dispatchers.IO) {
        documentDao.insertDocument(document)
        patientDao.touchUpdatedAt(document.patientId)
        mediaGateway?.onDocumentSaved(document)
    }

    suspend fun deleteDocument(documentId: String) = withContext(Dispatchers.IO) {
        val document = documentDao.getDocumentDirect(documentId)
        documentDao.deleteDocument(documentId)
        document?.let {
            patientDao.touchUpdatedAt(it.patientId)
            val gateway = mediaGateway
            if (gateway != null) {
                gateway.onDocumentDeleted(it)
                gateway.onRecordsChanged()
            } else {
                File(it.filePath).delete()
            }
        }
    }

    // ---- Unified search ----------------------------------------------------------------

    /**
     * Searches patient name/ID/diagnosis/fracture type, surgery procedure/diagnosis, and
     * OCR'd text from images/documents, and merges the hits into one ranked-by-relevance list.
     */
    fun searchEverything(rawQuery: String): Flow<List<PatientSearchResult>> {
        val query = rawQuery.trim()
        if (query.isEmpty()) return flowOf(emptyList())

        return combine(
            patientDao.searchPatients(query),
            surgeryDao.searchSurgeries(query),
            imageDao.searchByOcrText(query),
            documentDao.searchByOcrText(query),
            patientDao.getAllPatients()
        ) { patientMatches, surgeryMatches, imageMatches, documentMatches, allPatients ->
            val patientMap = allPatients.associateBy { it.id }
            val results = mutableListOf<PatientSearchResult>()

            patientMatches.forEach { p ->
                val matchType = when {
                    p.fullName.contains(query, ignoreCase = true) -> PatientSearchMatchType.NAME
                    p.patientCode.contains(query, ignoreCase = true) -> PatientSearchMatchType.PATIENT_ID
                    p.fractureClassification.contains(query, ignoreCase = true) -> PatientSearchMatchType.FRACTURE_TYPE
                    else -> PatientSearchMatchType.DIAGNOSIS
                }
                results.add(
                    PatientSearchResult(
                        patientId = p.id,
                        patientName = p.fullName,
                        patientCode = p.patientCode,
                        matchType = matchType,
                        snippet = p.diagnosis.ifBlank { p.fractureClassification }
                    )
                )
            }

            surgeryMatches.forEach { s ->
                val patient = patientMap[s.patientId] ?: return@forEach
                results.add(
                    PatientSearchResult(
                        patientId = patient.id,
                        patientName = patient.fullName,
                        patientCode = patient.patientCode,
                        matchType = PatientSearchMatchType.SURGERY,
                        snippet = s.procedure.ifBlank { s.diagnosis }
                    )
                )
            }

            (imageMatches.map { it.patientId to it.ocrText } + documentMatches.map { it.patientId to it.ocrText })
                .forEach { (patientId, ocrText) ->
                    val patient = patientMap[patientId] ?: return@forEach
                    results.add(
                        PatientSearchResult(
                            patientId = patient.id,
                            patientName = patient.fullName,
                            patientCode = patient.patientCode,
                            matchType = PatientSearchMatchType.OCR_TEXT,
                            snippet = snippetAround(ocrText, query)
                        )
                    )
                }

            results
        }.flowOn(Dispatchers.IO)
    }

    private fun snippetAround(text: String, query: String, radius: Int = 60): String {
        val index = text.indexOf(query, ignoreCase = true)
        if (index < 0) return text.take(120)
        val start = (index - radius).coerceAtLeast(0)
        val end = (index + query.length + radius).coerceAtMost(text.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + text.substring(start, end).trim() + suffix
    }

    // ---- Stats -------------------------------------------------------------------------

    suspend fun getPatientCount(): Int = withContext(Dispatchers.IO) { patientDao.getPatientCount() }

    suspend fun getPatientsByIdsOnce(ids: List<String>): List<PatientEntity> = withContext(Dispatchers.IO) {
        patientDao.getPatientsByIds(ids)
    }
}
