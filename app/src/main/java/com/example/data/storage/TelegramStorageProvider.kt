package com.example.data.storage

import android.content.Context
import android.os.StatFs
import com.example.data.crypto.CryptoKeys
import com.example.data.crypto.Hashing
import com.example.data.crypto.StreamCrypto
import com.example.data.crypto.WrappedKeyRepository
import com.example.data.local.dao.RemoteObjectDao
import com.example.data.local.entity.RemoteObjectEntity
import com.example.data.prefs.CachePolicy
import com.example.data.prefs.TelegramDriveRepository
import com.example.data.sync.DriveManifest
import com.example.data.sync.ManifestCodec
import com.example.data.telegram.DriveLog
import com.example.data.telegram.TelegramAuthState
import com.example.data.telegram.TelegramClient
import com.example.data.telegram.TelegramDownloadEvent
import com.example.data.telegram.TelegramException
import com.example.data.telegram.TelegramUploadEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Telegram Drive as a [StorageProvider].
 *
 * Shape of the design:
 *  - Telegram holds the canonical bytes. A local cache under `cacheDir/tgdrive`
 *    fronts it so the PDF generator, annotation canvas and Coil keep receiving
 *    ordinary `File`s and never learn the backend changed.
 *  - Every object is AES-256-GCM encrypted before upload by default. Patient
 *    imaging is medical data; the plaintext must not sit in a Telegram channel
 *    even a private one. The plaintext SHA-256 travels in the manifest, so
 *    integrity is verifiable without decrypting the whole file.
 *  - The caption carries a [DriveManifest], making the channel self-describing.
 *    That is what allows "rebuild manifest" to recover the local index after a
 *    reinstall, and it is why the drive is a real backup and not just a mirror.
 *  - [delete] is soft: the row is tombstoned and the caption rewritten. Bytes are
 *    only removed by [purge] after the retention window.
 */
class TelegramStorageProvider(
    private val context: Context,
    private val client: TelegramClient,
    private val remoteObjectDao: RemoteObjectDao,
    private val manifestCodec: ManifestCodec,
    private val streamCrypto: StreamCrypto,
    private val wrappedKeys: WrappedKeyRepository,
    private val driveRepository: TelegramDriveRepository
) : StorageProvider {

    override val backend: StorageBackend = StorageBackend.TELEGRAM

    private val cacheRoot: File by lazy {
        File(context.cacheDir, CACHE_DIR).also { it.mkdirs() }
    }

    private val workRoot: File by lazy {
        File(context.cacheDir, WORK_DIR).also { it.mkdirs() }
    }

    override suspend fun isReady(): Boolean =
        client.authState.value == TelegramAuthState.Ready && driveRepository.chatId() != null

    private suspend fun requireChat(): Long =
        driveRepository.chatId() ?: throw StorageException(
            "Telegram Drive is not initialised yet",
            retryable = true
        )

    private fun cacheFile(key: StorageKey): File = File(cacheRoot, key.value)

    // ---- Upload --------------------------------------------------------------

    override fun put(
        key: StorageKey,
        source: File,
        kind: StorageObjectKind,
        mimeType: String
    ): Flow<TransferProgress> = flow {
        emit(TransferProgress.Queued)
        if (!source.exists() || source.length() == 0L) {
            emit(TransferProgress.Failed("Source file is missing or empty", retryable = false))
            return@flow
        }

        val chatId = requireChat()
        val limits = runCatching { client.getLimits() }.getOrNull()
        if (limits != null && source.length() > limits.maxFileBytes) {
            emit(
                TransferProgress.Failed(
                    "File is larger than this Telegram account allows " +
                            "(${source.length() / MIB} MB of ${limits.maxFileBytes / MIB} MB)",
                    retryable = false
                )
            )
            return@flow
        }

        val plainHash = Hashing.sha256(source)
        val existing = remoteObjectDao.get(key.value)

        // Duplicate detection: identical bytes already at this key means nothing needs uploading.
        if (existing != null && existing.deletedAt == null &&
            plainHash != null && existing.contentHash == plainHash
        ) {
            emit(TransferProgress.Completed(existing.toStorageObject()))
            return@flow
        }

        val encrypt = driveRepository.encryptUploads()
        val payload = if (encrypt) encryptToWork(source, key) else source

        try {
            val manifest = DriveManifest(
                storageKey = key.value,
                name = key.fileName,
                kind = kind.name,
                mimeType = mimeType,
                sizeBytes = source.length(),
                contentHash = plainHash,
                encrypted = encrypt,
                createdAt = existing?.syncedAt ?: System.currentTimeMillis(),
                modifiedAt = source.lastModified(),
                revision = (existing?.revision ?: 0) + 1,
                patientId = patientIdOf(key)
            )
            val caption = manifestCodec.encode(manifest, encrypt)

            var completed: RemoteObjectEntity? = null
            client.uploadDocument(
                chatId = chatId,
                localPath = payload.absolutePath,
                fileName = remoteFileNameFor(key, encrypt),
                mimeType = if (encrypt) ENCRYPTED_MIME else mimeType,
                caption = caption,
                thumbnailPath = null
            ).collect { event ->
                when (event) {
                    is TelegramUploadEvent.Started -> Unit
                    is TelegramUploadEvent.Progress ->
                        emit(TransferProgress.Running(event.transferredBytes, event.totalBytes))

                    is TelegramUploadEvent.Completed -> {
                        val document = event.document
                        completed = RemoteObjectEntity(
                            storageKey = key.value,
                            kind = kind.name,
                            chatId = document.chatId,
                            messageId = document.messageId,
                            remoteFileId = document.remoteFileId,
                            uniqueFileId = document.uniqueFileId,
                            sizeBytes = source.length(),
                            contentHash = plainHash,
                            mimeType = mimeType,
                            encrypted = encrypt,
                            modifiedAt = source.lastModified(),
                            syncedAt = System.currentTimeMillis(),
                            deletedAt = null,
                            revision = manifest.revision
                        )
                    }
                }
            }

            val row = completed ?: run {
                emit(TransferProgress.Failed("Upload ended without confirmation", retryable = true))
                return@flow
            }

            remoteObjectDao.upsert(row)

            // The superseded message is removed only after the replacement is
            // confirmed, so an interrupted replace can never leave zero copies.
            if (existing != null && existing.messageId != row.messageId) {
                if (driveRepository.keepVersionHistory()) {
                    DriveLog.d(TAG, "Kept revision ${existing.revision} of ${key.fileName}")
                } else {
                    runCatching { client.deleteMessages(chatId, listOf(existing.messageId)) }
                        .onFailure { DriveLog.w(TAG, "Could not remove superseded message", it) }
                }
            }

            DriveLog.i(TAG, "Uploaded ${key.fileName} rev${row.revision} (${row.sizeBytes} B)")
            emit(TransferProgress.Completed(row.toStorageObject()))
        } catch (e: CancellationException) {
            // A collector that stops early cancels this flow. Emitting here would
            // break flow transparency, and nobody is left to receive it anyway.
            throw e
        } catch (e: TelegramException) {
            DriveLog.w(TAG, "Upload failed for ${key.fileName}: ${e.code}", e)
            emit(TransferProgress.Failed(e.message, retryable = e.isRateLimit || e.isNetworkFailure))
        } catch (e: Exception) {
            DriveLog.e(TAG, "Upload error for ${key.fileName}", e)
            emit(TransferProgress.Failed(e.message ?: "Upload failed", retryable = true))
        } finally {
            if (payload !== source) payload.delete()
        }
    }.flowOn(Dispatchers.IO)

    private fun encryptToWork(source: File, key: StorageKey): File {
        val target = File(workRoot, "${key.fileName}.${System.nanoTime()}.enc")
        target.parentFile?.mkdirs()
        val contentKey = wrappedKeys.getOrCreate(CryptoKeys.CONTENT)
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                streamCrypto.encryptStream(contentKey, input, output)
            }
        }
        return target
    }

    // ---- Download ------------------------------------------------------------

    override fun get(key: StorageKey, destination: File): Flow<TransferProgress> = flow {
        emit(TransferProgress.Queued)
        val row = remoteObjectDao.get(key.value)
        if (row == null) {
            emit(TransferProgress.Failed("No manifest entry for ${key.fileName}", retryable = false))
            return@flow
        }

        try {
            var downloadedPath: String? = null
            client.downloadDocument(row.remoteFileId).collect { event ->
                when (event) {
                    is TelegramDownloadEvent.Progress ->
                        emit(TransferProgress.Running(event.transferredBytes, event.totalBytes))

                    is TelegramDownloadEvent.Completed -> downloadedPath = event.localPath
                }
            }

            val tdPath = downloadedPath ?: run {
                emit(TransferProgress.Failed("Download ended without a file", retryable = true))
                return@flow
            }

            destination.parentFile?.mkdirs()
            val staged = File(tdPath)
            if (row.encrypted) {
                val contentKey = wrappedKeys.get(CryptoKeys.CONTENT) ?: run {
                    emit(
                        TransferProgress.Failed(
                            "This drive is encrypted with a key that is not on this device",
                            retryable = false
                        )
                    )
                    return@flow
                }
                staged.inputStream().use { input ->
                    destination.outputStream().use { output ->
                        streamCrypto.decryptStream(contentKey, input, output)
                    }
                }
            } else {
                staged.copyTo(destination, overwrite = true)
            }

            // Hash verification catches a truncated or tampered transfer before
            // the file reaches a patient record.
            val actual = Hashing.sha256(destination)
            if (row.contentHash != null && actual != null && actual != row.contentHash) {
                destination.delete()
                emit(TransferProgress.Failed("Integrity check failed for ${key.fileName}", retryable = true))
                return@flow
            }

            // TDLib's copy is redundant once the plaintext is on disk.
            runCatching { client.discardLocalCopy(row.remoteFileId) }

            emit(TransferProgress.Running(destination.length(), destination.length()))
            emit(TransferProgress.Completed(row.toStorageObject()))
        } catch (e: CancellationException) {
            // Same rule on the read path: a cancelled download must propagate,
            // not turn into a Failed event nobody can observe.
            throw e
        } catch (e: TelegramException) {
            emit(TransferProgress.Failed(e.message, retryable = e.isRateLimit || e.isNetworkFailure))
        } catch (e: Exception) {
            DriveLog.e(TAG, "Download error for ${key.fileName}", e)
            emit(TransferProgress.Failed(e.message ?: "Download failed", retryable = true))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun materialize(key: StorageKey): File? = withContext(Dispatchers.IO) {
        peek(key)?.let { return@withContext it }
        val cached = cacheFile(key)
        val terminal = get(key, cached).awaitTerminal()
        if (terminal is TransferProgress.Completed) {
            cached.setLastModified(System.currentTimeMillis())
            cached
        } else {
            null
        }
    }

    override suspend fun peek(key: StorageKey): File? = withContext(Dispatchers.IO) {
        cacheFile(key).takeIf { it.exists() && it.length() > 0 }
    }

    // ---- Metadata ------------------------------------------------------------

    override suspend fun exists(key: StorageKey): Boolean =
        remoteObjectDao.get(key.value)?.let { it.deletedAt == null } == true

    override suspend fun stat(key: StorageKey): StorageObject? =
        remoteObjectDao.get(key.value)?.takeIf { it.deletedAt == null }?.toStorageObject()

    override suspend fun list(prefix: String): List<StorageObject> =
        remoteObjectDao.getByPrefix(prefix).map { it.toStorageObject() }

    override suspend fun delete(key: StorageKey) {
        val row = remoteObjectDao.get(key.value) ?: return
        val now = System.currentTimeMillis()
        remoteObjectDao.markDeleted(key.value, now)
        cacheFile(key).delete()

        // Rewriting the caption is what carries the deletion to other devices;
        // the bytes stay until purge, so this is recoverable.
        runCatching {
            val manifest = DriveManifest(
                storageKey = key.value,
                name = key.fileName,
                kind = row.kind,
                mimeType = row.mimeType,
                sizeBytes = row.sizeBytes,
                contentHash = row.contentHash,
                encrypted = row.encrypted,
                createdAt = row.syncedAt,
                modifiedAt = row.modifiedAt,
                deletedAt = now,
                revision = row.revision,
                patientId = patientIdOf(key)
            )
            client.editCaption(row.chatId, row.messageId, manifestCodec.encode(manifest, row.encrypted))
        }.onFailure { DriveLog.w(TAG, "Could not tombstone ${key.fileName} remotely", it) }
    }

    /** Undoes a soft delete, for the rollback action in settings. */
    suspend fun restore(key: StorageKey): Boolean {
        val row = remoteObjectDao.get(key.value) ?: return false
        if (row.deletedAt == null) return true
        remoteObjectDao.restore(key.value)
        runCatching {
            val manifest = DriveManifest(
                storageKey = key.value,
                name = key.fileName,
                kind = row.kind,
                mimeType = row.mimeType,
                sizeBytes = row.sizeBytes,
                contentHash = row.contentHash,
                encrypted = row.encrypted,
                createdAt = row.syncedAt,
                modifiedAt = row.modifiedAt,
                deletedAt = null,
                revision = row.revision,
                patientId = patientIdOf(key)
            )
            client.editCaption(row.chatId, row.messageId, manifestCodec.encode(manifest, row.encrypted))
        }
        return true
    }

    override suspend fun purge(olderThanMillis: Long): Int {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        val purgeable = remoteObjectDao.getPurgeable(cutoff)
        if (purgeable.isEmpty()) return 0
        purgeable.groupBy { it.chatId }.forEach { (chatId, rows) ->
            runCatching { client.deleteMessages(chatId, rows.map { it.messageId }) }
                .onFailure { DriveLog.w(TAG, "Purge could not delete ${rows.size} messages", it) }
        }
        purgeable.forEach { remoteObjectDao.hardDelete(it.storageKey) }
        DriveLog.i(TAG, "Purged ${purgeable.size} tombstoned objects")
        return purgeable.size
    }

    override suspend fun verify(key: StorageKey): Boolean = withContext(Dispatchers.IO) {
        val row = remoteObjectDao.get(key.value) ?: return@withContext false
        val cached = cacheFile(key)
        if (!cached.exists()) {
            // Nothing cached: the remote message still existing is the strongest
            // claim available without spending bandwidth on a full re-download.
            return@withContext client.getDocument(row.chatId, row.messageId) != null
        }
        val actual = Hashing.sha256(cached) ?: return@withContext false
        row.contentHash == null || row.contentHash == actual
    }

    override suspend fun usage(): StorageUsage {
        val used = remoteObjectDao.totalLiveBytes()
        val count = remoteObjectDao.getAllLive().size
        val stat = StatFs(context.cacheDir.absolutePath)
        return StorageUsage(
            backend = StorageBackend.TELEGRAM,
            usedBytes = used,
            objectCount = count,
            availableBytes = stat.availableBlocksLong * stat.blockSizeLong,
            // Telegram imposes no account-level cloud quota, only a per-file cap.
            quotaBytes = null
        )
    }

    override suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        val bytes = cacheRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() } +
                workRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        cacheRoot.deleteRecursively()
        workRoot.deleteRecursively()
        cacheRoot.mkdirs()
        workRoot.mkdirs()
        DriveLog.i(TAG, "Cleared $bytes bytes of drive cache")
        bytes
    }

    /**
     * Evicts cached files according to the configured [CachePolicy]. Canonical data
     * under `filesDir` is untouched — everything in `cacheRoot` is re-downloadable.
     */
    suspend fun trimCacheTo(budgetBytes: Long): Long = withContext(Dispatchers.IO) {
        val policy = driveRepository.current().cachePolicy
        if (policy == CachePolicy.KEEP_EVERYTHING) return@withContext 0L

        val files = cacheRoot.walkTopDown().filter { it.isFile }.toMutableList()
        var freed = 0L

        if (policy == CachePolicy.MINIMAL) {
            val iterator = files.iterator()
            while (iterator.hasNext()) {
                val file = iterator.next()
                val rel = file.relativeToOrNull(cacheRoot)?.path?.replace(File.separatorChar, '/') ?: ""
                val isThumbnail = rel.contains("/images/thumbs/")
                if (!isThumbnail) {
                    val size = file.length()
                    if (file.delete()) {
                        freed += size
                        iterator.remove()
                    }
                }
            }
        }

        var total = files.sumOf { it.length() }
        if (total > budgetBytes) {
            files.sortBy { it.lastModified() }
            for (file in files) {
                if (total <= budgetBytes) break
                val size = file.length()
                if (file.delete()) {
                    total -= size
                    freed += size
                }
            }
        }
        if (freed > 0) DriveLog.d(TAG, "Trimmed $freed bytes from drive cache ($policy)")
        freed
    }

    /**
     * The remote file name deliberately carries no patient identifier — the name
     * is visible in Telegram's UI even when the caption is encrypted.
     */
    private fun remoteFileNameFor(key: StorageKey, encrypted: Boolean): String {
        val opaque = Hashing.sha256(key.value.toByteArray(Charsets.UTF_8)).take(16)
        return if (encrypted) "$opaque.otenc" else "$opaque.${key.extension.ifEmpty { "bin" }}"
    }

    private fun patientIdOf(key: StorageKey): String? =
        key.value.removePrefix(StorageKey.PATIENTS_PREFIX)
            .takeIf { key.startsWith(StorageKey.PATIENTS_PREFIX) }
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }

    companion object {
        private const val TAG = "TelegramStorage"
        private const val CACHE_DIR = "tgdrive"
        private const val WORK_DIR = "tgdrive-work"
        private const val ENCRYPTED_MIME = "application/octet-stream"
        private const val MIB = 1024L * 1024L

        /** Default on-device cache ceiling for remote objects: 512 MB. */
        const val DEFAULT_CACHE_BUDGET_BYTES = 512L * 1024 * 1024
    }
}

internal fun RemoteObjectEntity.toStorageObject(): StorageObject = StorageObject(
    key = StorageKey(storageKey),
    kind = runCatching { StorageObjectKind.valueOf(kind) }.getOrDefault(StorageObjectKind.OTHER),
    sizeBytes = sizeBytes,
    contentHash = contentHash,
    modifiedAt = modifiedAt,
    mimeType = mimeType,
    encrypted = encrypted,
    remoteOnly = true
)
