package com.example.data.extensions

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.example.data.local.entity.MediaType
import com.example.data.local.entity.PatientEntity
import com.example.data.local.entity.PatientImageEntity
import com.example.data.repository.PatientRepository
import com.example.utils.ImageCompressor
import com.example.utils.MediaMetadata
import com.example.utils.OcrProcessor
import com.example.utils.PatientMediaStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * `OrthoTips.patients` (permission `patients`): extensions read, create and
 * update patient records and add media — never delete. Everything goes through
 * [PatientRepository] and the same media path as the app's camera (compression,
 * thumbnail, OCR), so the patient list refreshes and Telegram Drive keeps
 * syncing (and encrypting) as for any record entered by hand. Extensions only
 * exchange plain values; they never deal with encryption.
 *
 * Called from the bridge's worker thread, hence the blocking functions.
 */
class ExtensionPatients(context: Context, private val repository: PatientRepository) {

    private val app = context.applicationContext

    fun search(query: String, limit: Int): JSONArray = runBlocking {
        val q = query.trim()
        val summaries = if (q.isEmpty()) repository.getActivePatientSummaries().first()
        else repository.searchPatientSummaries(q).first()
        val ids = summaries.sortedByDescending { it.updatedAt }.take(limit.coerceIn(1, ExtensionPatientRules.MAX_SEARCH_RESULTS)).map { it.id }
        val byId = repository.getPatientsByIdsOnce(ids).associateBy { it.id }
        JSONArray(ids.mapNotNull { byId[it] }.map(ExtensionPatientRules::summaryJson))
    }

    /** The full record with its media list, or null. */
    fun get(id: String): JSONObject? = runBlocking {
        val patient = repository.getPatient(id) ?: return@runBlocking null
        val media = repository.getPatientDetail(id).first()?.images.orEmpty()
        ExtensionPatientRules.toJson(patient).put("media", JSONArray(media.map { m ->
            JSONObject().put("id", m.id).put("category", m.category).put("mediaType", m.mediaType)
                .put("capturedAt", m.capturedAt).put("notes", m.notes)
        }))
    }

    fun getByCode(code: String): JSONObject? = runBlocking {
        val c = code.trim()
        if (c.isEmpty()) return@runBlocking null
        repository.searchPatientSummaries(c).first().firstOrNull { it.patientCode.equals(c, ignoreCase = true) }
    }?.let { get(it.id) }

    /** Returns `{ id, patientCode }`. A missing patientCode gets the app's next "P-0001" style code. */
    fun create(fields: JSONObject): JSONObject = runBlocking {
        val now = System.currentTimeMillis()
        val blank = PatientEntity(id = UUID.randomUUID().toString(), patientCode = "", fullName = "", createdAt = now, updatedAt = now)
        var patient = ExtensionPatientRules.apply(blank, fields)
        patient = if (patient.patientCode.isBlank()) patient.copy(patientCode = nextCode()) else {
            if (!repository.isPatientCodeAvailable(patient.patientCode)) throw IllegalArgumentException("Patient code '${patient.patientCode}' is already used")
            patient
        }
        repository.savePatient(patient)
        JSONObject().put("id", patient.id).put("patientCode", patient.patientCode)
    }

    /** Only the given fields change. Returns `{ id, patientCode }`. */
    fun update(id: String, fields: JSONObject): JSONObject = runBlocking {
        val existing = repository.getPatient(id) ?: throw IllegalArgumentException("Unknown patient '$id'")
        val patient = ExtensionPatientRules.apply(existing, fields)
        if (patient.patientCode.isBlank()) throw IllegalArgumentException("patientCode cannot be empty")
        if (patient.patientCode != existing.patientCode && !repository.isPatientCodeAvailable(patient.patientCode, id)) {
            throw IllegalArgumentException("Patient code '${patient.patientCode}' is already used")
        }
        repository.savePatient(patient)
        JSONObject().put("id", patient.id).put("patientCode", patient.patientCode)
    }

    /** Adds an image or a video to the patient. Returns `{ id, mediaType }`. */
    fun addMedia(patientId: String, args: JSONObject): JSONObject = runBlocking {
        repository.getPatient(patientId) ?: throw IllegalArgumentException("Unknown patient '$patientId'")
        val mime = args.optString("mimeType")
        val kind = ExtensionPatientRules.mediaKind(mime)
        val category = ExtensionPatientRules.category(if (args.isNull("category")) null else args.optString("category"))
        val notes = args.optString("notes").take(5_000)
        val base64 = args.optString("base64")
        if (base64.isEmpty()) throw IllegalArgumentException("Missing media data")
        if (base64.length.toLong() * 3 / 4 > ExtensionPatientRules.MAX_MEDIA_BYTES) throw IllegalArgumentException("Media too large (max 30 MB)")
        val capturedAtArg = if (args.isNull("capturedAt")) null else args.optDouble("capturedAt").takeIf { it.isFinite() && it > 0 }?.toLong()

        val tmpDir = File(app.cacheDir, "extension_media").apply { mkdirs() }
        val tmp = File(tmpDir, UUID.randomUUID().toString())
        try {
            tmp.writeBytes(Base64.decode(base64, Base64.DEFAULT))
            val mediaId = UUID.randomUUID().toString()
            val thumbFile = PatientMediaStorage.newThumbnailFile(app, patientId, mediaId)
            val entity = if (kind == "image") {
                val imageFile = PatientMediaStorage.newImageFile(app, patientId, mediaId)
                val capturedAt = capturedAtArg ?: MediaMetadata.capturedAt(tmp)
                try {
                    ImageCompressor.compressToFile(app.contentResolver, Uri.fromFile(tmp), imageFile)
                } catch (e: Exception) {
                    imageFile.delete()
                    throw IllegalArgumentException("Could not read this image ($mime)")
                }
                if (!imageFile.isFile || imageFile.length() == 0L) throw IllegalArgumentException("Could not read this image ($mime)")
                ImageCompressor.writeThumbnail(imageFile, thumbFile)
                PatientImageEntity(
                    id = mediaId, patientId = patientId, category = category.name,
                    filePath = imageFile.absolutePath, thumbnailPath = thumbFile.takeIf { it.exists() }?.absolutePath,
                    ocrText = runCatching { OcrProcessor.extractText(app, imageFile) }.getOrDefault(""),
                    notes = notes, capturedAt = capturedAt
                )
            } else {
                val videoFile = PatientMediaStorage.newVideoFile(app, patientId, mediaId, ExtensionPatientRules.videoExtension(mime))
                tmp.copyTo(videoFile, overwrite = true)
                val uri = Uri.fromFile(videoFile)
                MediaMetadata.videoThumbnail(app, uri)?.let { frame ->
                    thumbFile.outputStream().use { out -> frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out) }
                    frame.recycle()
                }
                PatientImageEntity(
                    id = mediaId, patientId = patientId, category = category.name,
                    filePath = videoFile.absolutePath, thumbnailPath = thumbFile.takeIf { it.exists() }?.absolutePath,
                    mediaType = MediaType.VIDEO.name, durationMs = MediaMetadata.durationMs(app, uri) ?: 0L,
                    notes = notes, capturedAt = capturedAtArg ?: MediaMetadata.capturedAt(app, uri)
                )
            }
            repository.saveImage(entity)
            JSONObject().put("id", entity.id).put("mediaType", entity.mediaType)
        } finally {
            tmp.delete()
        }
    }

    /** Same shape as the app's own suggestion ("P-0042"), skipping codes already used. */
    private suspend fun nextCode(): String {
        var n = repository.getPatientCount() + 1
        while (true) {
            val code = "P-" + n.toString().padStart(4, '0')
            if (repository.isPatientCodeAvailable(code)) return code
            n++
        }
    }
}
