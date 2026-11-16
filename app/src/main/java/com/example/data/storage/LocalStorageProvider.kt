package com.example.data.storage

import android.content.Context
import android.os.StatFs
import com.example.data.crypto.Hashing
import com.example.data.telegram.DriveLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The historical behaviour, now behind [StorageProvider].
 *
 * Every key resolves to the exact path [com.example.utils.PatientMediaStorage]
 * already produces, so an existing install keeps reading and writing the same
 * bytes in the same places. Nothing is migrated, moved or rewritten when this
 * class is introduced — that is the whole point of routing the old paths through
 * the new interface rather than inventing a new layout.
 *
 * Soft delete is implemented with a sibling `.trash` directory so a mis-tap in
 * the patient screen is recoverable, matching the data-safety rule that nothing
 * is destroyed without an explicit purge.
 */
class LocalStorageProvider(
    private val context: Context,
    private val rootDir: File = File(context.filesDir, "")
) : StorageProvider {

    override val backend: StorageBackend = StorageBackend.LOCAL

    private val trashRoot: File get() = File(context.filesDir, TRASH_DIR)

    override suspend fun isReady(): Boolean = true

    private fun resolve(key: StorageKey): File = File(rootDir, key.value)

    private fun trashFile(key: StorageKey): File = File(trashRoot, key.value)

    override fun put(
        key: StorageKey,
        source: File,
        kind: StorageObjectKind,
        mimeType: String
    ): Flow<TransferProgress> = flow {
        emit(TransferProgress.Queued)
        if (!source.exists()) {
            emit(TransferProgress.Failed("Source file is missing", retryable = false))
            return@flow
        }
        val destination = resolve(key)
        // A put onto the same file is the common case for locally captured media:
        // the camera already wrote to the final path, so copying would be wasted IO.
        if (destination.absolutePath != source.absolutePath) {
            destination.parentFile?.mkdirs()
            emit(TransferProgress.Running(0, source.length()))
            source.copyTo(destination, overwrite = true)
        }
        emit(TransferProgress.Running(destination.length(), destination.length()))
        emit(
            TransferProgress.Completed(
                StorageObject(
                    key = key,
                    kind = kind,
                    sizeBytes = destination.length(),
                    contentHash = Hashing.sha256(destination),
                    modifiedAt = destination.lastModified(),
                    mimeType = mimeType
                )
            )
        )
    }.flowOn(Dispatchers.IO)

    override fun get(key: StorageKey, destination: File): Flow<TransferProgress> = flow {
        emit(TransferProgress.Queued)
        val source = resolve(key)
        if (!source.exists()) {
            emit(TransferProgress.Failed("Object not found locally", retryable = false))
            return@flow
        }
        if (destination.absolutePath != source.absolutePath) {
            destination.parentFile?.mkdirs()
            source.copyTo(destination, overwrite = true)
        }
        emit(TransferProgress.Running(destination.length(), destination.length()))
        emit(
            TransferProgress.Completed(
                StorageObject(
                    key = key,
                    kind = kindOf(key),
                    sizeBytes = destination.length(),
                    contentHash = Hashing.sha256(destination),
                    modifiedAt = destination.lastModified(),
                    mimeType = mimeTypeOf(key)
                )
            )
        )
    }.flowOn(Dispatchers.IO)

    override suspend fun materialize(key: StorageKey): File? = peek(key)

    override suspend fun peek(key: StorageKey): File? = withContext(Dispatchers.IO) {
        resolve(key).takeIf { it.exists() && it.length() > 0 }
    }

    override suspend fun exists(key: StorageKey): Boolean = peek(key) != null

    override suspend fun stat(key: StorageKey): StorageObject? = withContext(Dispatchers.IO) {
        val file = resolve(key)
        if (!file.exists()) return@withContext null
        StorageObject(
            key = key,
            kind = kindOf(key),
            sizeBytes = file.length(),
            contentHash = null, // hashing on stat would make list screens crawl
            modifiedAt = file.lastModified(),
            mimeType = mimeTypeOf(key)
        )
    }

    override suspend fun list(prefix: String): List<StorageObject> = withContext(Dispatchers.IO) {
        val base = File(rootDir, prefix)
        if (!base.exists()) return@withContext emptyList()
        base.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { file ->
                val relative = file.relativeToOrNull(rootDir)?.path?.replace(File.separatorChar, '/')
                    ?: return@mapNotNull null
                val key = runCatching { StorageKey(relative) }.getOrNull() ?: return@mapNotNull null
                StorageObject(
                    key = key,
                    kind = kindOf(key),
                    sizeBytes = file.length(),
                    contentHash = null,
                    modifiedAt = file.lastModified(),
                    mimeType = mimeTypeOf(key)
                )
            }
            .toList()
    }

    override suspend fun delete(key: StorageKey) = withContext(Dispatchers.IO) {
        val file = resolve(key)
        if (!file.exists()) return@withContext
        val target = trashFile(key)
        target.parentFile?.mkdirs()
        val moved = file.renameTo(target)
        if (!moved) {
            // Cross-filesystem rename can fail; copy-then-delete keeps the
            // recoverable copy rather than silently hard-deleting.
            runCatching {
                file.copyTo(target, overwrite = true)
                file.delete()
            }.onFailure { DriveLog.w(TAG, "Soft delete fell back and failed for ${key.fileName}", it) }
        }
        DriveLog.d(TAG, "Soft-deleted ${key.fileName}")
    }

    override suspend fun purge(olderThanMillis: Long): Int = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        if (!trashRoot.exists()) return@withContext 0
        var removed = 0
        trashRoot.walkBottomUp().forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                if (file.delete()) removed++
            } else if (file.isDirectory && file != trashRoot && file.listFiles()?.isEmpty() == true) {
                file.delete()
            }
        }
        if (removed > 0) DriveLog.i(TAG, "Purged $removed trashed objects")
        removed
    }

    /** Restores a soft-deleted object. Used by the rollback path in settings. */
    suspend fun restore(key: StorageKey): Boolean = withContext(Dispatchers.IO) {
        val trashed = trashFile(key)
        if (!trashed.exists()) return@withContext false
        val destination = resolve(key)
        destination.parentFile?.mkdirs()
        trashed.renameTo(destination) || runCatching {
            trashed.copyTo(destination, overwrite = true); trashed.delete()
        }.getOrDefault(false)
    }

    override suspend fun verify(key: StorageKey): Boolean = withContext(Dispatchers.IO) {
        val file = resolve(key)
        file.exists() && file.length() > 0 && Hashing.sha256(file) != null
    }

    override suspend fun usage(): StorageUsage = withContext(Dispatchers.IO) {
        var bytes = 0L
        var count = 0
        File(rootDir, StorageKey.PATIENTS_PREFIX).walkTopDown().forEach {
            if (it.isFile) {
                bytes += it.length()
                count++
            }
        }
        val stat = StatFs(context.filesDir.absolutePath)
        StorageUsage(
            backend = StorageBackend.LOCAL,
            usedBytes = bytes,
            objectCount = count,
            availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        )
    }

    /**
     * Local storage has no cache tier — its files are the canonical copy — so the
     * only thing this can safely reclaim is the expired trash.
     */
    override suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        if (!trashRoot.exists()) return@withContext 0L
        val bytes = trashRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        if (trashRoot.deleteRecursively()) bytes else 0L
    }

    private fun kindOf(key: StorageKey): StorageObjectKind = when {
        key.startsWith(StorageKey.SNAPSHOT_PREFIX) -> StorageObjectKind.DATABASE_SNAPSHOT
        key.value.contains("/images/thumbs/") -> StorageObjectKind.PATIENT_THUMBNAIL
        key.value.contains("/images/") -> StorageObjectKind.PATIENT_IMAGE
        key.value.contains("/documents/") -> StorageObjectKind.PATIENT_DOCUMENT
        else -> StorageObjectKind.OTHER
    }

    private fun mimeTypeOf(key: StorageKey): String = when (key.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "json" -> "application/json"
        "mp4" -> "video/mp4"
        "m4a", "aac" -> "audio/mp4"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "LocalStorageProvider"
        private const val TRASH_DIR = ".trash"

        /** Retention before a soft-deleted object may be purged: 30 days. */
        const val DEFAULT_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
