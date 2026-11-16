package com.example.data.storage

/**
 * Which backend holds the canonical copy of user-generated content.
 *
 * LOCAL is the historical behaviour and stays the default: app-private files
 * under `filesDir/patients/...`, exactly as [com.example.utils.PatientMediaStorage]
 * has always laid them out. TELEGRAM adds a remote canonical copy with a local
 * cache in front of it. Neither mode ever deletes the other's data implicitly.
 */
enum class StorageBackend {
    LOCAL,
    TELEGRAM;

    companion object {
        fun parse(raw: String?): StorageBackend =
            entries.firstOrNull { it.name == raw } ?: LOCAL
    }
}

/** What a stored object is, which decides caching and sync priority. */
enum class StorageObjectKind {
    PATIENT_IMAGE,
    PATIENT_THUMBNAIL,
    PATIENT_DOCUMENT,
    /** A JSON snapshot of every Room table, used for record sync and recovery. */
    DATABASE_SNAPSHOT,
    /** Imported bone-atlas figures from the offline importer. */
    LIBRARY_FIGURE,
    OTHER;

    val isThumbnail: Boolean get() = this == PATIENT_THUMBNAIL
}

/**
 * A backend-independent logical address. Always a forward-slash relative path so
 * the same key names an object on disk, in the manifest and in Telegram.
 *
 * The local layout is the source of the naming scheme, so an existing install's
 * files map onto keys without moving a single byte:
 *   patients/{patientId}/images/{imageId}.jpg
 *   patients/{patientId}/images/thumbs/{imageId}.jpg
 *   patients/{patientId}/documents/{documentId}.{ext}
 *   snapshots/ortho-db-{timestamp}.json
 */
@JvmInline
value class StorageKey(val value: String) {

    init {
        require(value.isNotBlank()) { "Storage key must not be blank" }
        require(!value.startsWith("/")) { "Storage key must be relative: $value" }
        require(!value.contains("..")) { "Storage key must not traverse: $value" }
    }

    val fileName: String get() = value.substringAfterLast('/')

    val extension: String get() = fileName.substringAfterLast('.', "")

    fun startsWith(prefix: String): Boolean = value.startsWith(prefix)

    override fun toString(): String = value

    companion object {
        fun patientImage(patientId: String, imageId: String): StorageKey =
            StorageKey("patients/$patientId/images/$imageId.jpg")

        fun patientThumbnail(patientId: String, imageId: String): StorageKey =
            StorageKey("patients/$patientId/images/thumbs/$imageId.jpg")

        fun patientDocument(patientId: String, documentId: String, extension: String): StorageKey =
            StorageKey("patients/$patientId/documents/$documentId.$extension")

        fun patientPrefix(patientId: String): String = "patients/$patientId/"

        /**
         * Snapshot keys carry their row and patient counts:
         * `ortho-db-{millis}-r{rows}-p{patients}.json` (older versions wrote
         * `ortho-db-{millis}-r{rows}.json` or `ortho-db-{millis}.json`).
         *
         * Restore has to pick a snapshot that actually contains patients, and
         * carrying the count in the key avoids downloading older empty backups
         * during recovery and manifest rebuilds.
         */
        fun snapshot(timestamp: Long, rowCount: Int, patientCount: Int? = null): StorageKey =
            if (patientCount != null) {
                StorageKey("snapshots/ortho-db-$timestamp-r$rowCount-p$patientCount.json")
            } else {
                StorageKey("snapshots/ortho-db-$timestamp-r$rowCount.json")
            }

        /** Total row count encoded in a snapshot key; null for pre-v2 keys. */
        fun snapshotRowCount(key: StorageKey): Int? =
            Regex("-r(\\d+)(?:-p\\d+)?\\.json$").find(key.fileName)?.groupValues?.get(1)?.toIntOrNull()

        /** Patient row count encoded in a snapshot key; null for pre-v3 keys. */
        fun snapshotPatientCount(key: StorageKey): Int? =
            Regex("-p(\\d+)\\.json$").find(key.fileName)?.groupValues?.get(1)?.toIntOrNull()

        const val SNAPSHOT_PREFIX = "snapshots/"
        const val PATIENTS_PREFIX = "patients/"
    }
}

/** Metadata about one stored object, as the backend currently sees it. */
data class StorageObject(
    val key: StorageKey,
    val kind: StorageObjectKind,
    val sizeBytes: Long,
    val contentHash: String?,
    val modifiedAt: Long,
    val mimeType: String,
    val encrypted: Boolean = false,
    /** True when only metadata is held locally and the bytes need fetching. */
    val remoteOnly: Boolean = false
)

/** Progress for a single put/get. Terminal events are [Completed] and [Failed]. */
sealed interface TransferProgress {
    data object Queued : TransferProgress

    data class Running(val transferredBytes: Long, val totalBytes: Long) : TransferProgress {
        val fraction: Float
            get() = if (totalBytes <= 0) 0f else (transferredBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    data class Completed(val obj: StorageObject) : TransferProgress
    data class Failed(val reason: String, val retryable: Boolean) : TransferProgress
}

/** What the backend reports about space. Telegram has no hard account quota. */
data class StorageUsage(
    val backend: StorageBackend,
    val usedBytes: Long,
    val objectCount: Int,
    /** Free device bytes for LOCAL; free cache budget for TELEGRAM. */
    val availableBytes: Long,
    /** Null when the backend imposes no practical ceiling. */
    val quotaBytes: Long? = null
)

/** Raised when a backend cannot satisfy a request. */
class StorageException(
    message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null
) : Exception(message, cause)
