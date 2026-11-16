package com.example.data.storage

import android.content.Context
import com.example.data.crypto.Hashing
import com.example.data.local.dao.RemoteObjectDao
import com.example.data.local.entity.PatientDocumentEntity
import com.example.data.local.entity.PatientImageEntity
import com.example.data.prefs.SyncFrequency
import com.example.data.prefs.TelegramDriveRepository
import com.example.data.sync.SyncEngine
import com.example.data.telegram.DriveLog
import com.example.work.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The seam between patient records and storage.
 *
 * [com.example.data.repository.PatientRepository] calls into this instead of
 * touching `File` directly, which is what turns the drive from a background
 * mirror into the actual backend: a saved radiograph is queued for upload, a
 * deleted one is tombstoned so the deletion propagates, and a record whose bytes
 * only exist in Telegram can still be opened.
 *
 * Every method is a no-op when Telegram Drive is off. That is deliberate — an
 * install that never enables the drive must behave exactly as it did before, so
 * the guard lives here rather than being repeated at each call site.
 */
class PatientMediaGateway(
    private val context: Context,
    private val registry: StorageProviderRegistry,
    private val syncEngine: SyncEngine,
    private val remoteObjectDao: RemoteObjectDao,
    private val driveRepository: TelegramDriveRepository
) {

    private val filesRoot: String by lazy { context.filesDir.absolutePath }

    private val scheduler by lazy { SyncScheduler(context) }

    /**
     * Derives the logical key from an absolute media path.
     *
     * Paths stored in the database are absolute and were produced by
     * `PatientMediaStorage`, so relativising against `filesDir` yields exactly
     * the key layout [StorageKey] defines. Returns null for anything outside the
     * app-private tree, which is the correct answer for a path that predates the
     * media store or came from an external picker.
     */
    fun keyFor(absolutePath: String?): StorageKey? {
        if (absolutePath.isNullOrBlank()) return null
        val normalised = absolutePath.removePrefix(filesRoot).removePrefix(File.separator)
        if (normalised == absolutePath && !absolutePath.startsWith(StorageKey.PATIENTS_PREFIX)) {
            return null
        }
        val relative = normalised.replace(File.separatorChar, '/')
        if (!relative.startsWith(StorageKey.PATIENTS_PREFIX)) return null
        return runCatching { StorageKey(relative) }.getOrNull()
    }

    /**
     * Returns a readable local file for a stored media path, fetching it from the
     * drive if the local copy is gone. Null when the bytes cannot be produced —
     * callers must treat that as "not available right now", not as "deleted".
     */
    suspend fun resolve(absolutePath: String?): File? = withContext(Dispatchers.IO) {
        if (absolutePath.isNullOrBlank()) return@withContext null
        val direct = File(absolutePath)
        if (direct.exists() && direct.length() > 0) return@withContext direct
        val key = keyFor(absolutePath) ?: return@withContext null
        if (!isDriveActive()) return@withContext null

        // Restored to the path the record already points at, rather than into a
        // cache directory. Everything downstream — the annotation canvas, the PDF
        // exporter, the dimension probe that decides whether to show a spinner —
        // works with that path directly, and edits have to land on the real file.
        direct.parentFile?.mkdirs()
        val terminal = registry.telegram.get(key, direct).awaitTerminal()
        if (terminal is TransferProgress.Completed) {
            if (terminal.obj.modifiedAt > 0) direct.setLastModified(terminal.obj.modifiedAt)
            DriveLog.d(TAG, "Fetched ${key.fileName} back from the drive")
            direct
        } else {
            val reason = (terminal as? TransferProgress.Failed)?.reason ?: "no result"
            DriveLog.w(TAG, "Could not fetch ${key.fileName}: $reason")
            null
        }
    }

    /** True when the bytes behind [absolutePath] are on this device already. */
    fun isLocallyAvailable(absolutePath: String?): Boolean {
        if (absolutePath.isNullOrBlank()) return false
        val file = File(absolutePath)
        return file.exists() && file.length() > 0
    }

    // ---- Write hooks ---------------------------------------------------------

    suspend fun onImageSaved(image: PatientImageEntity) {
        if (!isDriveActive()) return
        // The full image and its thumbnail are queued separately: grids need
        // thumbnails on a second device long before anyone opens the original.
        val mime = if (image.mediaType == com.example.data.local.entity.MediaType.VIDEO.name) {
            "video/" + image.filePath.substringAfterLast('.', "mp4")
        } else "image/jpeg"
        enqueue(image.filePath, StorageObjectKind.PATIENT_IMAGE, mime)
        enqueue(image.thumbnailPath, StorageObjectKind.PATIENT_THUMBNAIL, "image/jpeg")
        kick()
        onRecordsChanged()
    }

    /**
     * A patient, surgery, timeline entry or media row changed.
     *
     * Records travel in the whole-database snapshot, which was previously only
     * pushed during a sync run. That left a window — sometimes permanent under a
     * weekly schedule — where a patient existed on the phone and nowhere else,
     * while the backup card cheerfully showed its files as uploaded.
     */
    suspend fun onRecordsChanged() {
        val settings = driveRepository.current()
        if (!settings.setupCompleted || settings.backend != StorageBackend.TELEGRAM) return
        driveRepository.markRecordsDirty()
        if (settings.syncFrequency == SyncFrequency.MANUAL) return
        runCatching {
            scheduler.scheduleRecordSnapshot(
                wifiOnly = settings.syncOnWifiOnly,
                chargingOnly = settings.syncWhileChargingOnly
            )
        }.onFailure { DriveLog.w(TAG, "Could not schedule a record snapshot", it) }
    }

    suspend fun onDocumentSaved(document: PatientDocumentEntity) {
        if (!isDriveActive()) return
        enqueue(
            document.filePath,
            StorageObjectKind.PATIENT_DOCUMENT,
            document.mimeType.ifBlank { "application/octet-stream" }
        )
        kick()
        onRecordsChanged()
    }

    private suspend fun enqueue(path: String?, kind: StorageObjectKind, mimeType: String) {
        val key = keyFor(path) ?: return
        val file = File(path!!)
        if (!file.exists() || file.length() == 0L) return
        runCatching { syncEngine.enqueueUpload(key, file, kind, mimeType) }
            .onFailure { DriveLog.w(TAG, "Could not queue ${key.fileName}", it) }
    }

    // ---- Delete hooks --------------------------------------------------------

    /**
     * Soft-deletes media for a removed image. The local file is moved to the
     * recoverable trash rather than unlinked, and a tombstone is recorded so the
     * next sync removes it from the drive instead of the drive resurrecting it.
     */
    suspend fun onImageDeleted(image: PatientImageEntity) {
        remove(image.filePath, "patient image deleted")
        remove(image.thumbnailPath, "patient image deleted")
    }

    suspend fun onDocumentDeleted(document: PatientDocumentEntity) {
        remove(document.filePath, "patient document deleted")
    }

    suspend fun onPatientMediaDeleted(
        images: List<PatientImageEntity>,
        documents: List<PatientDocumentEntity>
    ) {
        images.forEach { onImageDeleted(it) }
        documents.forEach { onDocumentDeleted(it) }
    }

    private suspend fun remove(path: String?, reason: String) = withContext(Dispatchers.IO) {
        if (path.isNullOrBlank()) return@withContext
        val key = keyFor(path)
        if (key == null) {
            // Outside the managed tree, so nothing is tracking it; the old
            // behaviour of deleting outright is still the right one.
            File(path).delete()
            return@withContext
        }
        runCatching { registry.local.delete(key) }
            .onFailure { DriveLog.w(TAG, "Soft delete failed for ${key.fileName}", it) }
        if (driveRepository.current().setupCompleted) {
            runCatching { syncEngine.recordDeletion(key, reason) }
                .onFailure { DriveLog.w(TAG, "Could not record deletion", it) }
        }
    }

    /**
     * Undoes a soft delete. Present so a mis-tap in the patient screen is
     * recoverable, and so a failed migration can be rolled back.
     */
    suspend fun restore(absolutePath: String?): Boolean = withContext(Dispatchers.IO) {
        val key = keyFor(absolutePath) ?: return@withContext false
        val localRestored = registry.local.restore(key)
        if (localRestored) return@withContext true
        if (!isDriveActive()) return@withContext false
        registry.telegram.restore(key) && registry.telegram.materialize(key) != null
    }

    // ---- Prefetch ------------------------------------------------------------

    /**
     * Warms the cache for a set of media paths, skipping anything already local.
     * Used when a patient record is opened so scrolling their imaging does not
     * stall on a per-image round trip.
     */
    suspend fun prefetch(paths: List<String?>): Int = withContext(Dispatchers.IO) {
        if (!isDriveActive()) return@withContext 0
        var fetched = 0
        for (path in paths) {
            if (isLocallyAvailable(path)) continue
            val key = keyFor(path) ?: continue
            if (registry.telegram.peek(key) != null) continue
            if (registry.telegram.materialize(key) != null) fetched++
        }
        fetched
    }

    /** True when the drive is set up and selected as the backup target. */
    suspend fun isDriveEnabled(): Boolean = isDriveActive()

    /**
     * True when a database snapshot newer than [modifiedAt] is in the drive.
     * Records travel in the whole-database snapshot rather than per patient, so
     * this is the honest test for "this patient's fields are safe".
     */
    suspend fun recordsBackedUpSince(modifiedAt: Long): Boolean {
        if (!isDriveActive()) return false
        return remoteObjectDao.getByPrefix(StorageKey.SNAPSHOT_PREFIX).any { row ->
            val key = runCatching { StorageKey(row.storageKey) }.getOrNull() ?: return@any false
            (StorageKey.snapshotRowCount(key) ?: 0) > 0 && row.modifiedAt >= modifiedAt
        }
    }

    private suspend fun isDriveActive(): Boolean {
        val settings = driveRepository.current()
        return settings.setupCompleted && settings.backend == StorageBackend.TELEGRAM
    }

    private suspend fun kick() {
        val settings = driveRepository.current()
        runCatching {
            scheduler.kickTransfers(settings.syncOnWifiOnly, settings.syncWhileChargingOnly)
        }.onFailure { DriveLog.w(TAG, "Could not schedule the transfer worker", it) }
    }

    // ---- Backup state and offloading -----------------------------------------

    /**
     * Whether one stored object is safely in the drive.
     *
     * "Safely" means a live manifest row whose plaintext hash matches the bytes on
     * disk. A row alone is not enough: an edited radiograph keeps its path, so a
     * stale row would report a file as backed up when the drive holds the version
     * from before the edit.
     */
    suspend fun isBackedUp(absolutePath: String?): Boolean = withContext(Dispatchers.IO) {
        val key = keyFor(absolutePath) ?: return@withContext false
        val row = remoteObjectDao.get(key.value) ?: return@withContext false
        if (row.deletedAt != null) return@withContext false
        val local = File(absolutePath!!)
        if (!local.exists()) {
            // Already offloaded: the drive is the only copy, which is the state
            // this whole feature exists to reach.
            return@withContext true
        }
        val hash = Hashing.sha256(local) ?: return@withContext false
        row.contentHash == null || row.contentHash == hash
    }

    /** Bytes held locally for [absolutePath], or zero if it is already offloaded. */
    fun localBytes(absolutePath: String?): Long {
        if (absolutePath.isNullOrBlank()) return 0
        val file = File(absolutePath)
        return if (file.exists()) file.length() else 0
    }

    /**
     * Removes local full-size bytes for objects already safe in the drive.
     *
     * Emphatically not a delete. No tombstone is written, the manifest row stays
     * live, and the database rows are untouched — the patient keeps every field,
     * every surgery and every thumbnail. Opening an offloaded image fetches it
     * back through [resolve].
     *
     * Anything not verifiably backed up is skipped, so a half-finished upload can
     * never cost the only copy of an image.
     */
    suspend fun offload(paths: List<String?>): OffloadResult = withContext(Dispatchers.IO) {
        if (!isDriveActive()) {
            return@withContext OffloadResult(0, 0, paths.count { !it.isNullOrBlank() })
        }
        var freed = 0L
        var removed = 0
        var skipped = 0
        for (path in paths) {
            if (path.isNullOrBlank()) continue
            val file = File(path)
            if (!file.exists()) continue
            if (!isBackedUp(path)) {
                skipped++
                continue
            }
            val size = file.length()
            // A hard delete on purpose: the bytes are in the drive, so parking a
            // copy in .trash would defeat the point of freeing space.
            if (file.delete()) {
                freed += size
                removed++
            } else {
                skipped++
            }
        }
        DriveLog.i(TAG, "Offloaded $removed objects, freed $freed bytes, skipped $skipped")
        OffloadResult(removed, freed, skipped)
    }

    // ---- Across every patient -------------------------------------------------

    /**
     * How much local space could be reclaimed across the whole library.
     *
     * Walks the manifest index rather than every patient in turn: keys mirror the
     * on-disk layout, so one pass over the live rows answers the question without
     * a query per patient.
     */
    suspend fun offloadableSummary(): DriveSpaceSummary = withContext(Dispatchers.IO) {
        if (!isDriveActive()) return@withContext DriveSpaceSummary()
        var bytes = 0L
        var files = 0
        val patients = mutableSetOf<String>()
        for (path in offloadCandidates()) {
            val file = File(path)
            if (!file.exists()) continue
            if (!isBackedUp(path)) continue
            bytes += file.length()
            files++
            keyFor(path)?.let { key ->
                key.value.removePrefix(StorageKey.PATIENTS_PREFIX).substringBefore('/')
                    .takeIf { it.isNotBlank() }?.let(patients::add)
            }
        }
        DriveSpaceSummary(bytes, files, patients.size)
    }

    /** Frees every verified-backed-up file across all patients. */
    suspend fun offloadAll(): OffloadResult = offload(offloadCandidates())

    /**
     * Full-size local paths for objects the drive already holds. Thumbnails are
     * excluded by kind, which is what keeps the gallery browsable afterwards.
     */
    private suspend fun offloadCandidates(): List<String> =
        remoteObjectDao.getAllLive()
            .asSequence()
            .filter { it.kind != StorageObjectKind.PATIENT_THUMBNAIL.name }
            .filter { it.storageKey.startsWith(StorageKey.PATIENTS_PREFIX) }
            .map { File(context.filesDir, it.storageKey).absolutePath }
            .toList()

    companion object {
        private const val TAG = "PatientMediaGateway"
    }
}

/** Library-wide reclaimable space, for the banner and the navigation badge. */
data class DriveSpaceSummary(
    val reclaimableBytes: Long = 0,
    val files: Int = 0,
    val patients: Int = 0
) {
    val hasSomethingToFree: Boolean get() = reclaimableBytes > 0
}

data class OffloadResult(
    val objectsRemoved: Int,
    val bytesFreed: Long,
    val objectsSkipped: Int
)

/**
 * How much of one patient's content is safely in the drive, and how much local
 * space could be reclaimed without losing anything.
 */
data class PatientDriveStatus(
    val patientId: String,
    val driveEnabled: Boolean,
    val totalMedia: Int,
    val backedUpMedia: Int,
    val recordsBackedUp: Boolean,
    val offloadableBytes: Long,
    val localBytes: Long
) {
    val mediaFullyBackedUp: Boolean get() = totalMedia > 0 && backedUpMedia == totalMedia

    /** Safe to free local space only when records and every file are both safe. */
    val canOffload: Boolean
        get() = driveEnabled && mediaFullyBackedUp && recordsBackedUp && offloadableBytes > 0

    val fullyBackedUp: Boolean
        get() = driveEnabled && recordsBackedUp && (totalMedia == 0 || mediaFullyBackedUp)
}
