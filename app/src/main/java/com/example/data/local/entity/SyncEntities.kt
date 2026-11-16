package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tables added in schema v5 for the optional Telegram Drive backend.
 *
 * All three are additive. No existing table is altered, no column is dropped and
 * no foreign key points at them, so a database that has never seen Telegram Drive
 * behaves identically whether these tables are empty or absent before migration.
 */

enum class TransferDirection { UPLOAD, DOWNLOAD }

enum class TransferState {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    /** Kept out of the queue but retained for the transfer history screen. */
    CANCELLED
}

/**
 * The manifest row for one object that lives in the drive channel. This is the
 * local index of remote state; losing it is recoverable by replaying the channel
 * captions (see the manifest rebuild action in settings).
 */
@Entity(
    tableName = "remote_objects",
    indices = [
        Index("uniqueFileId"),
        Index("kind"),
        Index("deletedAt"),
        Index("contentHash")
    ]
)
data class RemoteObjectEntity(
    /** The logical [com.example.data.storage.StorageKey] value. */
    @PrimaryKey val storageKey: String,
    val kind: String,
    val chatId: Long,
    val messageId: Long,
    /** Session-scoped; re-resolved after a reinstall. */
    val remoteFileId: String,
    /** Stable across sessions — the real remote identity. */
    val uniqueFileId: String,
    val sizeBytes: Long,
    /** SHA-256 of the plaintext bytes, so encryption never hides a corrupt file. */
    val contentHash: String?,
    val mimeType: String,
    val encrypted: Boolean,
    /** Last modification time of the source content, not of the upload. */
    val modifiedAt: Long,
    val syncedAt: Long,
    /** Non-null once soft-deleted; bytes stay in Telegram until purge. */
    val deletedAt: Long? = null,
    /** Bumped when a newer version supersedes this row, for version history. */
    val revision: Int = 1
)

/**
 * A queued transfer. Rows survive process death, which is what lets an
 * interrupted 300 MB MRI series resume instead of restarting.
 */
@Entity(
    tableName = "transfer_queue",
    indices = [
        Index("state"),
        Index("storageKey"),
        Index("nextAttemptAt")
    ]
)
data class TransferQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val storageKey: String,
    val direction: String,
    /** Absolute path of the local source (upload) or destination (download). */
    val localPath: String,
    val kind: String,
    val mimeType: String,
    val state: String = TransferState.PENDING.name,
    val attemptCount: Int = 0,
    /** Epoch millis; the queue skips rows scheduled for the future. */
    val nextAttemptAt: Long = 0,
    val bytesTransferred: Long = 0,
    val totalBytes: Long = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Records that something was deleted locally so the deletion propagates to the
 * drive instead of the next pull resurrecting it.
 */
@Entity(
    tableName = "sync_tombstones",
    indices = [Index("propagated")]
)
data class SyncTombstoneEntity(
    @PrimaryKey val storageKey: String,
    val deletedAt: Long,
    val reason: String,
    val propagated: Boolean = false
)
