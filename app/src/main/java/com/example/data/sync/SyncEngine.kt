package com.example.data.sync

import com.example.data.crypto.Hashing
import com.example.data.local.dao.RemoteObjectDao
import com.example.data.local.dao.SyncTombstoneDao
import com.example.data.local.dao.TransferQueueDao
import com.example.data.local.entity.SyncTombstoneEntity
import com.example.data.local.entity.TransferDirection
import com.example.data.local.entity.TransferQueueEntity
import com.example.data.local.entity.TransferState
import com.example.data.prefs.TelegramDriveRepository
import com.example.data.storage.LocalStorageProvider
import com.example.data.storage.StorageBackend
import com.example.data.storage.StorageException
import com.example.data.storage.awaitTerminal
import com.example.data.storage.StorageKey
import com.example.data.storage.StorageObjectKind
import com.example.data.storage.StorageProviderRegistry
import com.example.data.storage.TelegramStorageProvider
import com.example.data.storage.TransferProgress
import com.example.data.telegram.DriveLog
import com.example.data.telegram.TelegramClient
import kotlinx.coroutines.Dispatchers
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
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = System.currentTimeMillis()
) {
    val succeeded: Boolean get() = errors.isEmpty()
    val durationMillis: Long get() = finishedAt - startedAt
}

/**
 * Coordinates the drive: what to push, what to pull, and what to do when the two
 * sides disagree.
 *
 * The engine never transfers bytes itself. It plans work into `transfer_queue`
 * and lets [com.example.work.TransferQueueWorker] execute it under WorkManager
 * constraints, so a sync survives the app being backgrounded mid-upload and
 * resumes with the same backoff state.
 *
 * Deletion is propagated through tombstones rather than by absence. "Not in the
 * local set" is indistinguishable from "not downloaded yet", and treating those
 * the same is how sync engines destroy data.
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
    private val snapshotWorkDir: File
) {

    suspend fun sync(
        scope: SyncScope = SyncScope.INCREMENTAL,
        policy: ConflictPolicy = ConflictPolicy.NEWEST_WINS
    ): SyncReport = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        DriveLog.i(TAG, "Sync ${scope.name.lowercase()} started")

        if (driveRepository.backend() != StorageBackend.TELEGRAM) {
            DriveLog.w(TAG, "Sync skipped: Telegram Drive is not the selected backend")
            return@withContext SyncReport(
                scope = scope,
                errors = listOf("Telegram Drive is not the selected backend"),
                startedAt = startedAt
            )
        }
        if (!telegram.isReady()) {
            DriveLog.w(TAG, "Sync skipped: Telegram Drive is not connected")
            return@withContext SyncReport(
                scope = scope,
                errors = listOf("Telegram Drive is not connected"),
                startedAt = startedAt
            )
        }

        // Anything left RUNNING belongs to a process that no longer exists.
        transferQueueDao.requeueInterrupted(System.currentTimeMillis())

        val pulled = runCatching { pullManifest(scope) }
            .onFailure { errors += "Manifest pull failed: ${it.message}" }
            .getOrDefault(ManifestPull())

        if (pulled.unreadable > 0) {
            errors += "${pulled.unreadable} drive objects are encrypted with a key " +
                    "that is not on this device"
        }

        val tombstones = runCatching { propagateTombstones() }
            .onFailure { errors += "Deletion propagation failed: ${it.message}" }
            .getOrDefault(0)

        val plan = runCatching { planTransfers(scope, policy) }
            .onFailure { errors += "Planning failed: ${it.message}" }
            .getOrDefault(TransferPlan())

        val snapshotPushed = runCatching { pushDatabaseSnapshot() }
            .onFailure { errors += "Record snapshot failed: ${it.message}" }
            .getOrDefault(false)

        val finishedAt = System.currentTimeMillis()
        val report = SyncReport(
            scope = scope,
            uploadsQueued = plan.uploads,
            downloadsQueued = plan.downloads,
            manifestRowsSeen = pulled.seen,
            unreadableObjects = pulled.unreadable,
            tombstonesPropagated = tombstones,
            snapshotPushed = snapshotPushed,
            conflicts = plan.conflicts,
            errors = errors,
            startedAt = startedAt,
            finishedAt = finishedAt
        )
        if (report.succeeded) {
            driveRepository.recordSyncSuccess(finishedAt)
        } else {
            driveRepository.recordSyncFailure(errors.first())
        }
        DriveLog.i(
            TAG,
            "Sync ${scope.name.lowercase()} done in ${report.durationMillis}ms: " +
                    "${plan.uploads} up, ${plan.downloads} down, ${errors.size} errors"
        )
        report
    }

    // ---- Pull ----------------------------------------------------------------

    /**
     * Reads the drive channel and refreshes the local index from the captions.
     * On [SyncScope.FULL] the whole history is walked; incrementally it stops at
     * the first page that produced no new or changed rows.
     */
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
            if (page.documents.isEmpty()) break
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
                val unchanged = existing != null &&
                        existing.messageId == document.messageId &&
                        existing.revision == manifest.revision &&
                        existing.contentHash == manifest.contentHash &&
                        (existing.deletedAt != null) == (manifest.deletedAt != null)
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
            if (scope == SyncScope.INCREMENTAL && changedInPage == 0 && pages > 1) break
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
        DriveLog.i(TAG, "Rebuilding manifest index from the drive channel")
        remoteObjectDao.clear()
        pullManifest(SyncScope.FULL).seen
    }

    // ---- Planning ------------------------------------------------------------

    private data class TransferPlan(
        val uploads: Int = 0,
        val downloads: Int = 0,
        val conflicts: List<SyncConflict> = emptyList()
    )

    private suspend fun planTransfers(scope: SyncScope, policy: ConflictPolicy): TransferPlan {
        val localObjects = local.list(StorageKey.PATIENTS_PREFIX).associateBy { it.key.value }
        val remoteObjects = remoteObjectDao.getAllLive().associateBy { it.storageKey }
        val conflicts = mutableListOf<SyncConflict>()
        var uploads = 0
        var downloads = 0

        // Local content the drive does not have, or has an older version of.
        for ((keyValue, localObject) in localObjects) {
            if (tombstoneDao.get(keyValue) != null) continue
            val remote = remoteObjects[keyValue]
            val localFile = local.peek(localObject.key) ?: continue

            if (remote == null) {
                if (enqueue(localObject.key, localFile, TransferDirection.UPLOAD, localObject.kind, localObject.mimeType)) uploads++
                continue
            }

            val localHash = if (scope == SyncScope.FULL || remote.sizeBytes != localFile.length()) {
                Hashing.sha256(localFile)
            } else {
                null // same size and incremental: trust the index rather than re-hash
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

        // Drive content this device has never seen. Thumbnails are fetched eagerly
        // because grids need them; full-size media waits until it is opened.
        for ((keyValue, remote) in remoteObjects) {
            if (localObjects.containsKey(keyValue)) continue
            if (keyValue.startsWith(StorageKey.SNAPSHOT_PREFIX)) continue
            val kind = runCatching { StorageObjectKind.valueOf(remote.kind) }
                .getOrDefault(StorageObjectKind.OTHER)
            if (!kind.isThumbnail && scope != SyncScope.FULL) continue
            val key = runCatching { StorageKey(keyValue) }.getOrNull() ?: continue
            val destination = File(localRootDir, keyValue)
            if (enqueue(key, destination, TransferDirection.DOWNLOAD, kind, remote.mimeType)) downloads++
        }

        return TransferPlan(uploads, downloads, conflicts)
    }

    /**
     * Queues a single upload. Called by
     * [com.example.data.storage.PatientMediaGateway] when a record is saved, so
     * new media starts transferring immediately rather than waiting for the next
     * periodic sync to notice it.
     */
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

    /** Records a local deletion so the next sync removes it from the drive too. */
    suspend fun recordDeletion(key: StorageKey, reason: String) {
        tombstoneDao.upsert(
            SyncTombstoneEntity(
                storageKey = key.value,
                deletedAt = System.currentTimeMillis(),
                reason = reason
            )
        )
    }

    // ---- Records -------------------------------------------------------------

    /**
     * Exports the database and uploads it — unless doing so would bury a better
     * snapshot.
     *
     * A fresh install has an empty database. Uploading that produces a snapshot
     * that is newer than the one holding the user's actual records, and a later
     * restore picks it and returns nothing. Refusing the push is the only safe
     * behaviour: an empty local database is never evidence that the records were
     * deleted, only that this device has not been restored yet.
     */
    private suspend fun pushDatabaseSnapshot(): Boolean {
        val timestamp = System.currentTimeMillis()
        val staging = File(snapshotWorkDir, "ortho-db-$timestamp.json")
        val summary = databaseSnapshot.export(staging)

        if (summary.rowCount == 0 && driveHoldsRecords()) {
            staging.delete()
            DriveLog.w(
                TAG,
                "Local database is empty but the drive holds records; skipping the " +
                        "snapshot push so restore keeps seeing the good copy"
            )
            return false
        }

        // The channel was filling with identical 242 B snapshots, one per sync.
        // If the newest snapshot already holds exactly these bytes, there is
        // nothing to back up.
        val hash = Hashing.sha256(staging)
        val newest = remoteObjectDao.getByPrefix(StorageKey.SNAPSHOT_PREFIX)
            .maxByOrNull { it.modifiedAt }
        if (hash != null && newest?.contentHash == hash) {
            staging.delete()
            DriveLog.d(TAG, "Records unchanged since the last snapshot; nothing to upload")
            return false
        }

        val key = StorageKey.snapshot(timestamp, summary.rowCount)
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
            terminal is TransferProgress.Completed
        } finally {
            staging.delete()
        }
    }

    /** True when any snapshot in the drive is known to contain records. */
    private suspend fun driveHoldsRecords(): Boolean =
        remoteObjectDao.getByPrefix(StorageKey.SNAPSHOT_PREFIX).any { row ->
            val key = runCatching { StorageKey(row.storageKey) }.getOrNull() ?: return@any false
            (StorageKey.snapshotRowCount(key) ?: 0) > 0
        }

    /**
     * Fetches the newest snapshot from the drive and merges it. Used by the setup
     * wizard on a fresh install and by the explicit restore action.
     */
    suspend fun pullDatabaseSnapshot(): SnapshotSummary? = withContext(Dispatchers.IO) {
        val candidates = remoteObjectDao.getByPrefix(StorageKey.SNAPSHOT_PREFIX)
            .sortedByDescending { it.modifiedAt }
            .mapNotNull { runCatching { StorageKey(it.storageKey) }.getOrNull() }

        if (candidates.isEmpty()) {
            DriveLog.w(TAG, "No snapshot in the drive to restore from")
            return@withContext null
        }

        // Newest-first, skipping any whose key says it holds nothing. Keys written
        // before row counts existed carry no count, so those are still downloaded
        // and inspected rather than assumed empty.
        var inspected = 0
        for (key in candidates) {
            if (StorageKey.snapshotRowCount(key) == 0) continue
            if (inspected++ >= MAX_SNAPSHOT_CANDIDATES) break

            val file = telegram.materialize(key)
            if (file == null) {
                DriveLog.w(TAG, "Snapshot ${key.fileName} could not be fetched")
                continue
            }
            val summary = databaseSnapshot.inspect(file)
            if (summary == null || summary.rowCount == 0) {
                DriveLog.d(TAG, "Snapshot ${key.fileName} holds no records; trying an older one")
                continue
            }
            DriveLog.i(TAG, "Restoring ${summary.rowCount} rows from ${key.fileName}")
            return@withContext databaseSnapshot.import(file)
        }

        DriveLog.w(
            TAG,
            "Checked ${candidates.size} snapshots and none contained records. If the " +
                    "drive was written by another install, its encryption key is not on " +
                    "this device."
        )
        null
    }

    // ---- Integrity -----------------------------------------------------------

    /**
     * Walks the index and checks every entry still resolves. Returns the keys that
     * failed so the diagnostics screen can list them; nothing is deleted here.
     */
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

    /**
     * Removes snapshots known to hold no records, keeping the newest one as a
     * marker. Only ever touches empty snapshots, so a real backup can never be
     * pruned by this.
     */
    private suspend fun pruneEmptySnapshots(): Int {
        val snapshots = remoteObjectDao.getByPrefix(StorageKey.SNAPSHOT_PREFIX)
            .sortedByDescending { it.modifiedAt }
            .mapNotNull { row ->
                val key = runCatching { StorageKey(row.storageKey) }.getOrNull()
                    ?: return@mapNotNull null
                key to StorageKey.snapshotRowCount(key)
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
        private const val MAX_SNAPSHOT_CANDIDATES = 10
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
