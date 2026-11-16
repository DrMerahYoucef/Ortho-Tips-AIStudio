package com.example.data.sync

import com.example.data.crypto.Hashing
import com.example.data.local.dao.RemoteObjectDao
import com.example.data.local.dao.SyncTombstoneDao
import com.example.data.local.dao.TransferQueueDao
import com.example.data.local.entity.SyncTombstoneEntity
import com.example.data.local.entity.TransferDirection
import com.example.data.local.entity.TransferQueueEntity
import com.example.data.local.entity.TransferState
import com.example.data.prefs.CachePolicy
import com.example.data.prefs.TelegramDriveRepository
import com.example.data.storage.LocalStorageProvider
import com.example.data.storage.StorageBackend
import com.example.data.storage.StorageException
import com.example.data.storage.StorageKey
import com.example.data.storage.StorageObjectKind
import com.example.data.storage.StorageProviderRegistry
import com.example.data.storage.TelegramStorageProvider
import com.example.data.storage.TransferProgress
import com.example.data.storage.awaitTerminal
import com.example.data.telegram.DriveLog
import com.example.data.telegram.TelegramClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** What a sync run should cover. */
enum class SyncScope {
    /** Only what changed since the last successful run. The normal path. */
    INCREMENTAL,

    /** Re-examine everything, including objects the index believes are current. */
    FULL
}

/** How a divergence between local and remote is settled. */
enum class ConflictPolicy {
    /** Whichever side was modified last wins. The default. */
    NEWEST_WINS,

    /** The device is authoritative; the drive is overwritten. */
    PREFER_LOCAL,

    /** The drive is authoritative; local content is refreshed from it. */
    PREFER_REMOTE,

    /** Nothing is overwritten; divergences are reported for the user to settle. */
    ASK
}

data class SyncConflict(
    val key: StorageKey,
    val localModifiedAt: Long,
    val remoteModifiedAt: Long,
    val localHash: String?,
    val remoteHash: String?
)

data class SyncReport(
    val scope: SyncScope,
    val uploadsQueued: Int = 0,
    val downloadsQueued: Int = 0,
    val manifestRowsSeen: Int = 0,
    /** Drive objects this device holds no usable key for. */
    val unreadableObjects: Int = 0,
    val tombstonesPropagated: Int = 0,
    val snapshotPushed: Boolean = false,
    val snapshotPulled: Boolean = false,
    val conflicts: List<SyncConflict> = emptyList(),
    val errors: List<String> = emptyList(),
    val hasRetryableFailure: Boolean = false,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = System.currentTimeMillis()
) {
    val succeeded: Boolean get() = errors.isEmpty()
    val durationMillis: Long get() = finishedAt - startedAt
}

/**
 * Coordinates the drive: patient records (`patients`, `surgeries`, `timeline_events`,
 * `patient_images`, `patient_documents`) and patient media (`patients/...`).
 *
 * Non-patient tables (shifts, anatomical reference chapters/bones/sections/images,
 * library notes/bookmarks, and suggestions) are never synchronized.
 */
class SyncEngine(
    private val registry: StorageProviderRegistry,
    private val telegram: TelegramStorageProvider,
    private val local: LocalStorageProvider,
    private val client: TelegramClient,
    private val remoteObjectDao: RemoteObjectDao,
    private val transferQueueDao: TransferQueueDao,
    private val tombstoneDao: SyncTombstoneDao,
    private val manifestCodec: ManifestCodec,
    private val databaseSnapshot: DatabaseSnapshot,
    private val driveRepository: TelegramDriveRepository,
    /** Root the local provider resolves keys against; download destinations live here. */
    private val localRootDir: File,
    private val snapshotWorkDir: File,
    private val progressMonitor: SyncProgressMonitor = SyncProgressMonitor()
) {

    private val syncMutex = Mutex()

    suspend fun sync(
        scope: SyncScope = SyncScope.INCREMENTAL,
        policy: ConflictPolicy = ConflictPolicy.NEWEST_WINS
    ): SyncReport = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val startedAt = System.currentTimeMillis()
            val errors = mutableListOf<String>()
            var retryableFailure = false
            val initialPatients = databaseSnapshot.localPatientCount()
            progressMonitor.onSyncStarted(scope, initialPatients)
            DriveLog.i(TAG, "Sync ${scope.name.lowercase()} started (local patients: $initialPatients)")

            if (driveRepository.backend() != StorageBackend.TELEGRAM) {
                DriveLog.w(TAG, "Sync skipped: Telegram Drive is not the selected backend")
                progressMonitor.addExplanation(
                    SyncExplanation(ExplanationCode.DRIVE_NOT_SELECTED, ExplanationSeverity.ERROR)
                )
                val report = SyncReport(
                    scope = scope,
                    errors = listOf("Telegram Drive is not the selected backend"),
                    hasRetryableFailure = false,
                    startedAt = startedAt
                )
                progressMonitor.onSyncFinished(report, initialPatients)
                return@withLock report
            }
            if (!telegram.isReady()) {
                DriveLog.w(TAG, "Sync skipped: Telegram Drive is not connected")
                progressMonitor.addExplanation(
                    SyncExplanation(ExplanationCode.DRIVE_NOT_CONNECTED, ExplanationSeverity.ERROR)
                )
                val report = SyncReport(
                    scope = scope,
                    errors = listOf("Telegram Drive is not connected"),
                    hasRetryableFailure = true,
                    startedAt = startedAt
                )
                progressMonitor.onSyncFinished(report, initialPatients)
                return@withLock report
            }

            // Anything left RUNNING belongs to a process that no longer exists.
            transferQueueDao.requeueInterrupted(System.currentTimeMillis())

            progressMonitor.updatePhase(SyncPhase.PULLING_MANIFEST)
            val pulled = runCatching { pullManifest(scope) }
                .onFailure { err ->
                    errors += "Manifest pull failed: ${err.message}"
                    progressMonitor.addExplanation(
                        SyncExplanation(
                            ExplanationCode.MANIFEST_PULL_FAILED,
                            ExplanationSeverity.ERROR,
                            err.message
                        )
                    )
                    if (isRetryableError(err)) retryableFailure = true
                }
                .getOrDefault(ManifestPull())

            val remoteSnapshotsCount = remoteObjectDao.getByPrefix(StorageKey.SNAPSHOT_PREFIX)
                .count { isPotentiallyNonEmptySnapshotKey(it.storageKey) }
            progressMonitor.onManifestPulled(pulled.seen, pulled.unreadable, remoteSnapshotsCount)

            if (pulled.unreadable > 0) {
                errors += "${pulled.unreadable} drive objects are encrypted with a key " +
                        "that is not on this device"
            }

            progressMonitor.updatePhase(SyncPhase.SYNCING_DELETIONS)
            val tombstones = runCatching { propagateTombstones() }
                .onFailure { err ->
                    errors += "Deletion propagation failed: ${err.message}"
                    progressMonitor.addExplanation(
                        SyncExplanation(
                            ExplanationCode.DELETION_SYNC_FAILED,
                            ExplanationSeverity.ERROR,
                            err.message
                        )
                    )
                    if (isRetryableError(err)) retryableFailure = true
                }
                .getOrDefault(0)

            runCatching { reconcileRemoteDeletions() }
                .onFailure { err ->
                    errors += "Remote deletion sync failed: ${err.message}"
                    progressMonitor.addExplanation(
                        SyncExplanation(
                            ExplanationCode.DELETION_SYNC_FAILED,
                            ExplanationSeverity.ERROR,
                            err.message
                        )
                    )
                    if (isRetryableError(err)) retryableFailure = true
                }

            progressMonitor.updatePhase(SyncPhase.RESTORING_PATIENTS)
            val snapshotPulled = runCatching { pullNewerSnapshotIfAvailable() }
                .onFailure { err ->
                    errors += "Patient restore failed: ${err.message}"
                    progressMonitor.addExplanation(
                        SyncExplanation(
                            ExplanationCode.SNAPSHOT_RESTORE_FAILED,
                            ExplanationSeverity.ERROR,
                            err.message
                        )
                    )
                    if (isRetryableError(err)) retryableFailure = true
                }
                .getOrDefault(false)

            progressMonitor.updatePhase(SyncPhase.PLANNING_MEDIA)
            val plan = runCatching { planTransfers(scope, policy) }
                .onFailure { err ->
                    errors += "Media planning failed: ${err.message}"
                    progressMonitor.addExplanation(
                        SyncExplanation(
                            ExplanationCode.PLANNING_FAILED,
                            ExplanationSeverity.ERROR,
                            err.message
                        )
                    )
                    if (isRetryableError(err)) retryableFailure = true
                }
                .getOrDefault(TransferPlan())
            progressMonitor.onMediaPlanned(plan.uploads, plan.downloads)

            progressMonitor.updatePhase(SyncPhase.BACKING_UP_PATIENTS)
            val snapshotPushed = runCatching { pushDatabaseSnapshot() }
                .onFailure { err ->
                    errors += "Patient snapshot upload failed: ${err.message}"
                    progressMonitor.addExplanation(
                        SyncExplanation(
                            ExplanationCode.SNAPSHOT_PUSH_FAILED,
                            ExplanationSeverity.ERROR,
                            err.message
                        )
                    )
                    if (isRetryableError(err)) retryableFailure = true
                }
                .getOrDefault(false)

            val finalPatients = databaseSnapshot.localPatientCount()
            val finishedAt = System.currentTimeMillis()
            val report = SyncReport(
                scope = scope,
                uploadsQueued = plan.uploads,
                downloadsQueued = plan.downloads,
                manifestRowsSeen = pulled.seen,
                unreadableObjects = pulled.unreadable,
                tombstonesPropagated = tombstones,
                snapshotPushed = snapshotPushed,
                snapshotPulled = snapshotPulled,
                conflicts = plan.conflicts,
                errors = errors,
                hasRetryableFailure = retryableFailure,
                startedAt = startedAt,
                finishedAt = finishedAt
            )
            if (report.succeeded) {
                driveRepository.recordSyncSuccess(finishedAt)
            } else {
                driveRepository.recordSyncFailure(errors.first())
            }
            progressMonitor.onSyncFinished(report, finalPatients)
            DriveLog.i(
                TAG,
                "Sync ${scope.name.lowercase()} done in ${report.durationMillis}ms: " +
                        "patients=$finalPatients, ${plan.uploads} up, ${plan.downloads} down, ${errors.size} errors"
            )
            report
        }
    }

    private fun isRetryableError(error: Throwable): Boolean =
        (error as? StorageException)?.retryable ?: true

    // ---- Pull ----------------------------------------------------------------

    private data class ManifestPull(
        val seen: Int = 0,
        val unreadable: Int = 0,
        val foreign: Int = 0
    )

    private suspend fun pullManifest(scope: SyncScope): ManifestPull {
        val chatId = driveRepository.chatId() ?: return ManifestPull()
        var fromMessageId = 0L
        var seen = 0
        var unreadable = 0
        var foreign = 0
        var pages = 0

        while (pages < MAX_PAGES) {
            val page = client.fetchDocuments(chatId, fromMessageId, PAGE_SIZE)
            var changedInPage = 0

            for (document in page.documents) {
                val manifest = when (val decoded = manifestCodec.inspect(document.caption)) {
                    is ManifestCodec.Decoded.Ok -> decoded.manifest
                    ManifestCodec.Decoded.Unreadable -> {
                        unreadable++
                        continue
                    }

                    ManifestCodec.Decoded.NotOurs -> {
                        foreign++
                        continue
                    }
                }
                seen++
                val existing = remoteObjectDao.get(manifest.storageKey)
                // Messages walk newest-first; ignore older revisions when version history is kept.
                if (existing != null &&
                    (manifest.revision < existing.revision ||
                        (manifest.revision == existing.revision && document.messageId < existing.messageId))
                ) {
                    continue
                }
                val unchanged = existing != null &&
                        existing.messageId == document.messageId &&
                        existing.revision == manifest.revision &&
                        existing.contentHash == manifest.contentHash &&
                        existing.deletedAt == manifest.deletedAt
                if (unchanged) continue

                changedInPage++
                remoteObjectDao.upsert(
                    com.example.data.local.entity.RemoteObjectEntity(
                        storageKey = manifest.storageKey,
                        kind = manifest.kind,
                        chatId = document.chatId,
                        messageId = document.messageId,
                        remoteFileId = document.remoteFileId,
                        uniqueFileId = document.uniqueFileId,
                        sizeBytes = manifest.sizeBytes,
                        contentHash = manifest.contentHash,
                        mimeType = manifest.mimeType,
                        encrypted = manifest.encrypted,
                        modifiedAt = manifest.modifiedAt,
                        syncedAt = System.currentTimeMillis(),
                        deletedAt = manifest.deletedAt,
                        revision = manifest.revision
                    )
                )
            }

            pages++
            fromMessageId = page.nextFromMessageId
            if (fromMessageId == 0L) break
            if (scope == SyncScope.INCREMENTAL && page.documents.isNotEmpty() && changedInPage == 0 && pages > 1) {
                break
            }
        }
        DriveLog.i(
            TAG,
            "Manifest pull: $seen readable, $unreadable unreadable, $foreign foreign, " +
                    "over $pages pages"
        )
        if (unreadable > 0) {
            DriveLog.w(
                TAG,
                "$unreadable drive objects cannot be decrypted here. They were sealed " +
                        "with a different content key, so this install cannot read them."
            )
        }
        return ManifestPull(seen, unreadable, foreign)
    }

    /**
     * Discards the local index and replays the entire channel. This is the
     * recovery path after a reinstall or a corrupted index — the reason the
     * caption manifest exists at all.
     */
    suspend fun rebuildManifest(): Int = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            DriveLog.i(TAG, "Rebuilding manifest index from the drive channel")
            remoteObjectDao.clear()
            pullManifest(SyncScope.FULL).seen
        }
    }

    // ---- Planning ------------------------------------------------------------

    private data class TransferPlan(
        val uploads: Int = 0,
        val downloads: Int = 0,
        val conflicts: List<SyncConflict> = emptyList()
    )

    private suspend fun planTransfers(scope: SyncScope, policy: ConflictPolicy): TransferPlan {
        val cachePolicy = driveRepository.current().cachePolicy
        val localObjects = local.list(StorageKey.PATIENTS_PREFIX).associateBy { it.key.value }
        val remoteObjects = remoteObjectDao.getAllLive()
            .filter { it.storageKey.startsWith(StorageKey.PATIENTS_PREFIX) }
            .associateBy { it.storageKey }
        val conflicts = mutableListOf<SyncConflict>()
        var uploads = 0
        var downloads = 0

        // Local patient media the drive does not have, or has an older version of.
        for ((keyValue, localObject) in localObjects) {
            val remote = remoteObjects[keyValue]
            val tombstone = tombstoneDao.get(keyValue)
            if (tombstone != null) {
                if (tombstone.reason == REMOTE_DELETION_REASON && remote != null) {
                    tombstoneDao.delete(keyValue)
                } else {
                    continue
                }
            }
            val localFile = local.peek(localObject.key) ?: continue

            if (remote == null) {
                if (enqueue(localObject.key, localFile, TransferDirection.UPLOAD, localObject.kind, localObject.mimeType)) uploads++
                continue
            }

            val localHash = if (
                scope == SyncScope.FULL ||
                remote.sizeBytes != localFile.length() ||
                localFile.lastModified() != remote.modifiedAt
            ) {
                Hashing.sha256(localFile)
            } else {
                null // exact size and mtime match on incremental sync
            }
            val identical = localHash != null && localHash == remote.contentHash
            if (identical) continue
            if (localHash == null && remote.modifiedAt >= localFile.lastModified()) continue

            val localNewer = localFile.lastModified() > remote.modifiedAt
            val bothChanged = localHash != null && localHash != remote.contentHash &&
                    remote.modifiedAt > localFile.lastModified()

            when {
                bothChanged && policy == ConflictPolicy.ASK -> conflicts += SyncConflict(
                    key = localObject.key,
                    localModifiedAt = localFile.lastModified(),
                    remoteModifiedAt = remote.modifiedAt,
                    localHash = localHash,
                    remoteHash = remote.contentHash
                )

                policy == ConflictPolicy.PREFER_REMOTE ->
                    if (enqueue(localObject.key, localFile, TransferDirection.DOWNLOAD, localObject.kind, localObject.mimeType)) downloads++

                policy == ConflictPolicy.PREFER_LOCAL || localNewer ->
                    if (enqueue(localObject.key, localFile, TransferDirection.UPLOAD, localObject.kind, localObject.mimeType)) uploads++

                else ->
                    if (enqueue(localObject.key, localFile, TransferDirection.DOWNLOAD, localObject.kind, localObject.mimeType)) downloads++
            }
        }

        // Drive patient media this device has not stored locally yet, governed by CachePolicy.
        for ((keyValue, remote) in remoteObjects) {
            if (localObjects.containsKey(keyValue)) continue
            val key = runCatching { StorageKey(keyValue) }.getOrNull() ?: continue

            val tombstone = tombstoneDao.get(keyValue)
            if (tombstone != null) {
                if (tombstone.reason == REMOTE_DELETION_REASON) {
                    tombstoneDao.delete(keyValue)
                    if (local.restore(key)) continue
                } else {
                    continue
                }
            }

            val kind = runCatching { StorageObjectKind.valueOf(remote.kind) }
                .getOrDefault(StorageObjectKind.OTHER)
            if (kind == StorageObjectKind.LIBRARY_FIGURE) continue
            val shouldDownload = when (cachePolicy) {
                CachePolicy.KEEP_EVERYTHING -> true
                CachePolicy.HYBRID -> kind.isThumbnail || scope == SyncScope.FULL
                CachePolicy.MINIMAL -> kind.isThumbnail || policy == ConflictPolicy.PREFER_REMOTE
            }
            if (!shouldDownload) continue
            val destination = File(localRootDir, keyValue)
            if (enqueue(key, destination, TransferDirection.DOWNLOAD, kind, remote.mimeType)) downloads++
        }

        return TransferPlan(uploads, downloads, conflicts)
    }

    suspend fun enqueueUpload(
        key: StorageKey,
        file: File,
        kind: StorageObjectKind,
        mimeType: String
    ): Boolean = enqueue(key, file, TransferDirection.UPLOAD, kind, mimeType)

    private suspend fun enqueue(
        key: StorageKey,
        file: File,
        direction: TransferDirection,
        kind: StorageObjectKind,
        mimeType: String
    ): Boolean {
        if (transferQueueDao.findActiveFor(key.value) != null) return false
        transferQueueDao.insert(
            TransferQueueEntity(
                storageKey = key.value,
                direction = direction.name,
                localPath = file.absolutePath,
                kind = kind.name,
                mimeType = mimeType,
                state = TransferState.PENDING.name,
                totalBytes = if (direction == TransferDirection.UPLOAD) file.length() else 0
            )
        )
        return true
    }

    // ---- Deletions -----------------------------------------------------------

    private suspend fun propagateTombstones(): Int {
        val pending = tombstoneDao.getPending()
        var propagated = 0
        for (tombstone in pending) {
            val key = runCatching { StorageKey(tombstone.storageKey) }.getOrNull() ?: continue
            runCatching { telegram.delete(key) }
                .onSuccess {
                    tombstoneDao.markPropagated(tombstone.storageKey)
                    propagated++
                }
                .onFailure { DriveLog.w(TAG, "Could not propagate deletion", it) }
        }
        return propagated
    }

    /**
     * Moves local copies of remotely soft-deleted patient media to `.trash` and cancels
     * active transfer queue rows for them so deleted items stay deleted across devices.
     */
    private suspend fun reconcileRemoteDeletions() {
        val deleted = remoteObjectDao.getAllDeleted()
        for (row in deleted) {
            transferQueueDao.cancelActiveFor(row.storageKey)
            val key = runCatching { StorageKey(row.storageKey) }.getOrNull() ?: continue
            if (!key.startsWith(StorageKey.PATIENTS_PREFIX)) continue
            if (local.peek(key) != null) {
                local.delete(key)
                tombstoneDao.upsert(
                    SyncTombstoneEntity(
                        storageKey = key.value,
                        deletedAt = row.deletedAt ?: System.currentTimeMillis(),
                        reason = REMOTE_DELETION_REASON,
                        propagated = true
                    )
                )
            }
        }
    }

    /** Records a local deletion so the next sync removes it from the drive too. */
    suspend fun recordDeletion(key: StorageKey, reason: String) {
        transferQueueDao.cancelActiveFor(key.value)
        tombstoneDao.upsert(
            SyncTombstoneEntity(
                storageKey = key.value,
                deletedAt = System.currentTimeMillis(),
                reason = reason
            )
        )
    }

    // ---- Patient Records -----------------------------------------------------

    /**
     * Pulls and safely merges remote patient snapshots during sync.
     *
     * When the device has 0 patients locally, stale watermarks / last-hashes are bypassed
     * and candidate snapshots are inspected newest-first until finding the latest snapshot
     * that actually contains patient rows (skipping older atlas-only/empty snapshots).
     */
    private suspend fun pullNewerSnapshotIfAvailable(): Boolean {
        val localPatients = databaseSnapshot.localPatientCount()
        val watermark = driveRepository.snapshotWatermark()
        val lastHash = driveRepository.current().lastSnapshotHash

        val allSnapshots = remoteObjectDao.getByPrefix(StorageKey.SNAPSHOT_PREFIX)
            .sortedByDescending { it.modifiedAt }

        val candidates = allSnapshots.filter { row ->
            isPotentiallyNonEmptySnapshotKey(row.storageKey)
        }

        if (candidates.isEmpty()) return false

        if (localPatients == 0) {
            DriveLog.i(
                TAG,
                "Local DB has 0 patients; scanning ${candidates.size} Drive snapshots for patient backup"
            )
            var inspected = 0
            for (row in candidates) {
                val key = runCatching { StorageKey(row.storageKey) }.getOrNull() ?: continue
                if (inspected++ >= MAX_SNAPSHOT_CANDIDATES) break

                val file = telegram.materialize(key) ?: continue
                val summary = databaseSnapshot.inspect(file)
                if (summary == null || summary.patientCount == 0) {
                    DriveLog.d(
                        TAG,
                        "Snapshot ${key.fileName} contains 0 patients (empty/atlas-only); trying older backup"
                    )
                    continue
                }

                val imported = databaseSnapshot.import(file)
                val totalAfter = databaseSnapshot.localPatientCount()
                driveRepository.markSnapshotImported(
                    maxOf(imported.createdAt, row.modifiedAt),
                    imported.contentHash
                )
                progressMonitor.onPatientsRestored(imported.patientCount, totalAfter)
                DriveLog.i(
                    TAG,
                    "Auto-restored ${imported.patientCount} patients (${imported.rowCount} patient rows) from ${key.fileName}"
                )
                return true
            }

            DriveLog.w(TAG, "No snapshot in Drive contained patient rows")
            progressMonitor.addExplanation(
                SyncExplanation(ExplanationCode.NO_PATIENT_SNAPSHOT_FOUND, ExplanationSeverity.WARNING)
            )
            return false
        }

        // Normal multi-device sync when local DB already holds patients.
        val newerCandidates = candidates.filter { it.modifiedAt > watermark }
        var inspected = 0
        for (row in newerCandidates) {
            val key = runCatching { StorageKey(row.storageKey) }.getOrNull() ?: continue
            if (inspected++ >= MAX_SNAPSHOT_CANDIDATES) break

            val file = telegram.materialize(key) ?: continue
            val summary = databaseSnapshot.inspect(file) ?: continue
            if (summary.patientCount == 0) {
                DriveLog.d(TAG, "Skipping newer 0-patient snapshot ${key.fileName}")
                continue
            }

            if (summary.contentHash.isNotEmpty() && summary.contentHash == lastHash) {
                driveRepository.markSnapshotImported(row.modifiedAt, summary.contentHash)
                return false
            }

            val imported = databaseSnapshot.import(file)
            val totalAfter = databaseSnapshot.localPatientCount()
            driveRepository.markSnapshotImported(row.modifiedAt, imported.contentHash)
            progressMonitor.onPatientsRestored(imported.patientCount, totalAfter)
            DriveLog.i(
                TAG,
                "Merged ${imported.patientCount} patients (${imported.rowCount} patient rows) from newer remote snapshot ${key.fileName}"
            )
            return true
        }
        return false
    }

    /**
     * Exports ONLY patient-domain tables and uploads the snapshot — unless the device
     * has 0 patients locally while Drive holds snapshots, preventing empty installs from
     * burying real patient backups.
     */
    private suspend fun pushDatabaseSnapshot(): Boolean {
        val timestamp = System.currentTimeMillis()
        val staging = File(snapshotWorkDir, "ortho-db-$timestamp.json")
        val summary = databaseSnapshot.export(staging)

        if (summary.patientCount == 0 && driveHoldsRecords()) {
            staging.delete()
            DriveLog.w(
                TAG,
                "Local database has 0 patients while Drive holds snapshots; skipping snapshot push to protect remote backups"
            )
            progressMonitor.addExplanation(
                SyncExplanation(
                    ExplanationCode.EMPTY_PATIENT_OVERWRITE_BLOCKED,
                    ExplanationSeverity.INFO
                )
            )
            return false
        }

        if (summary.patientCount == 0 && summary.rowCount == 0) {
            staging.delete()
            DriveLog.d(TAG, "No local patient records to snapshot; skipping upload")
            return false
        }

        val newest = remoteObjectDao.getByPrefix(StorageKey.SNAPSHOT_PREFIX)
            .maxByOrNull { it.modifiedAt }
        val lastHash = driveRepository.current().lastSnapshotHash
        if (newest != null && summary.contentHash.isNotEmpty() && lastHash == summary.contentHash) {
            staging.delete()
            if (driveRepository.hasUnpushedRecords()) {
                driveRepository.markSnapshotPushed(newest.modifiedAt, summary.contentHash)
            }
            DriveLog.d(TAG, "Patient records unchanged since the last snapshot; nothing to upload")
            return false
        }

        val key = StorageKey.snapshot(timestamp, summary.rowCount, summary.patientCount)
        return try {
            val terminal = telegram.put(
                key,
                staging,
                StorageObjectKind.DATABASE_SNAPSHOT,
                "application/json"
            ).awaitTerminal()
            if (terminal is TransferProgress.Failed) {
                throw StorageException(terminal.reason, retryable = terminal.retryable)
            }
            if (terminal is TransferProgress.Completed) {
                driveRepository.markSnapshotPushed(summary.createdAt, summary.contentHash)
                progressMonitor.onPatientsBackedUp(summary.patientCount)
                true
            } else {
                false
            }
        } finally {
            staging.delete()
        }
    }

    /** True when any snapshot in the drive may contain patient records. */
    private suspend fun driveHoldsRecords(): Boolean =
        remoteObjectDao.getByPrefix(StorageKey.SNAPSHOT_PREFIX).any { row ->
            isPotentiallyNonEmptySnapshotKey(row.storageKey)
        }

    private fun isPotentiallyNonEmptySnapshotKey(storageKey: String): Boolean {
        val key = runCatching { StorageKey(storageKey) }.getOrNull() ?: return false
        val patientCount = StorageKey.snapshotPatientCount(key)
        val rowCount = StorageKey.snapshotRowCount(key)
        // Legacy snapshots lack -p{N} (patientCount == null) and might carry -r1475 from old exports.
        if (patientCount != null) return patientCount > 0
        if (rowCount != null) return rowCount > 0
        return true
    }

    /**
     * Fetches the newest snapshot from the drive that actually contains patients and merges
     * ONLY the patient tables into the local database.
     */
    suspend fun pullDatabaseSnapshot(): SnapshotSummary? = withContext(Dispatchers.IO) {
        val candidates = remoteObjectDao.getByPrefix(StorageKey.SNAPSHOT_PREFIX)
            .sortedByDescending { it.modifiedAt }

        if (candidates.isEmpty()) {
            DriveLog.w(TAG, "No snapshot in the drive to restore from")
            progressMonitor.addExplanation(
                SyncExplanation(ExplanationCode.NO_PATIENT_SNAPSHOT_FOUND, ExplanationSeverity.WARNING)
            )
            return@withContext null
        }

        var inspected = 0
        for (row in candidates) {
            val key = runCatching { StorageKey(row.storageKey) }.getOrNull() ?: continue
            if (!isPotentiallyNonEmptySnapshotKey(row.storageKey)) continue
            if (inspected++ >= MAX_SNAPSHOT_CANDIDATES) break

            val file = telegram.materialize(key)
            if (file == null) {
                DriveLog.w(TAG, "Snapshot ${key.fileName} could not be fetched")
                continue
            }
            val summary = databaseSnapshot.inspect(file)
            if (summary == null || summary.patientCount == 0) {
                DriveLog.d(TAG, "Snapshot ${key.fileName} holds 0 patients; trying an older one")
                continue
            }
            DriveLog.i(TAG, "Restoring ${summary.patientCount} patients (${summary.rowCount} rows) from ${key.fileName}")
            val imported = databaseSnapshot.import(file)
            val totalAfter = databaseSnapshot.localPatientCount()
            driveRepository.markSnapshotImported(
                maxOf(imported.createdAt, row.modifiedAt),
                imported.contentHash
            )
            progressMonitor.onPatientsRestored(imported.patientCount, totalAfter)
            return@withContext imported
        }

        DriveLog.w(
            TAG,
            "Checked ${candidates.size} snapshots and none contained patient records."
        )
        progressMonitor.addExplanation(
            SyncExplanation(ExplanationCode.NO_PATIENT_SNAPSHOT_FOUND, ExplanationSeverity.WARNING)
        )
        null
    }

    // ---- Integrity -----------------------------------------------------------

    suspend fun verifyIntegrity(): IntegrityReport = withContext(Dispatchers.IO) {
        val rows = remoteObjectDao.getAllLive()
        val missing = mutableListOf<String>()
        val corrupt = mutableListOf<String>()
        for (row in rows) {
            val key = runCatching { StorageKey(row.storageKey) }.getOrNull() ?: continue
            val document = runCatching { client.getDocument(row.chatId, row.messageId) }.getOrNull()
            if (document == null) {
                missing += row.storageKey
                continue
            }
            if (!telegram.verify(key)) corrupt += row.storageKey
        }
        val orphans = transferQueueDao.getFailed().count {
            remoteObjectDao.get(it.storageKey) == null && !File(it.localPath).exists()
        }
        IntegrityReport(
            checked = rows.size,
            missingRemotely = missing,
            hashMismatches = corrupt,
            orphanedTransfers = orphans
        )
    }

    /** Removes cached bytes and finished queue rows that no longer serve anything. */
    suspend fun cleanUp(retentionMillis: Long): CleanupReport = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - retentionMillis
        val trimmedHistory = transferQueueDao.trimHistory(cutoff)
        val trimmedTombstones = tombstoneDao.trim(cutoff)
        val budget = driveRepository.current().cacheBudgetMb.toLong() * 1024 * 1024
        val freed = telegram.trimCacheTo(budget)
        val purgedLocal = local.purge(retentionMillis)
        val prunedSnapshots = pruneEmptySnapshots()
        CleanupReport(trimmedHistory, trimmedTombstones, freed, purgedLocal, prunedSnapshots)
    }

    private suspend fun pruneEmptySnapshots(): Int {
        val snapshots = remoteObjectDao.getByPrefix(StorageKey.SNAPSHOT_PREFIX)
            .sortedByDescending { it.modifiedAt }
            .mapNotNull { row ->
                val key = runCatching { StorageKey(row.storageKey) }.getOrNull()
                    ?: return@mapNotNull null
                key to (StorageKey.snapshotPatientCount(key) ?: StorageKey.snapshotRowCount(key))
            }
        val empties = snapshots.filter { it.second == 0 }.drop(1)
        empties.forEach { (key, _) ->
            runCatching { telegram.delete(key) }
                .onFailure { DriveLog.w(TAG, "Could not prune ${key.fileName}", it) }
        }
        if (empties.isNotEmpty()) DriveLog.i(TAG, "Pruned ${empties.size} empty snapshots")
        return empties.size
    }

    companion object {
        private const val TAG = "SyncEngine"
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 200
        private const val MAX_SNAPSHOT_CANDIDATES = 25
        private const val REMOTE_DELETION_REASON = "remote deletion"
    }
}

data class IntegrityReport(
    val checked: Int,
    val missingRemotely: List<String>,
    val hashMismatches: List<String>,
    val orphanedTransfers: Int
) {
    val healthy: Boolean
        get() = missingRemotely.isEmpty() && hashMismatches.isEmpty() && orphanedTransfers == 0
}

data class CleanupReport(
    val transferRowsTrimmed: Int,
    val tombstonesTrimmed: Int,
    val cacheBytesFreed: Long,
    val localObjectsPurged: Int,
    val emptySnapshotsPruned: Int = 0
)
