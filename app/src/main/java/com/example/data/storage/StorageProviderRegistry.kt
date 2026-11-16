package com.example.data.storage

import com.example.data.prefs.TelegramDriveRepository
import com.example.data.telegram.DriveLog
import java.io.File

/**
 * Resolves which [StorageProvider] is active and provides the hybrid read path.
 *
 * Repositories depend on this rather than on either concrete provider, so
 * business logic stays storage-independent. Two rules make the switch safe:
 *
 *  1. Reads always fall back to local. If Telegram is selected but the device is
 *     offline or the drive is unreachable, a locally present file is still
 *     returned. A surgeon in an operating room with no signal must still see the
 *     radiograph they captured this morning.
 *  2. Writes go to the active backend *and*, in KEEP_EVERYTHING and HYBRID cache
 *     policies, leave a local copy behind. Nothing is removed from local storage
 *     as a side effect of enabling Telegram Drive.
 */
class StorageProviderRegistry(
    val local: LocalStorageProvider,
    val telegram: TelegramStorageProvider,
    private val driveRepository: TelegramDriveRepository
) {

    /** The provider the user selected. May not be ready. */
    suspend fun active(): StorageProvider = when (driveRepository.backend()) {
        StorageBackend.LOCAL -> local
        StorageBackend.TELEGRAM -> telegram
    }

    /**
     * The provider that can actually serve a read right now. Falls back to local
     * whenever Telegram is selected but unavailable, rather than failing.
     */
    suspend fun readable(): StorageProvider {
        val selected = active()
        if (selected.backend == StorageBackend.LOCAL) return local
        return if (selected.isReady()) selected else local
    }

    fun forBackend(backend: StorageBackend): StorageProvider = when (backend) {
        StorageBackend.LOCAL -> local
        StorageBackend.TELEGRAM -> telegram
    }

    /**
     * Resolves [key] to a readable local file, trying local first (free and
     * instant) and only then the drive. This is the single entry point the rest
     * of the app should use when it needs a `File` for a stored object.
     */
    suspend fun resolve(key: StorageKey): File? {
        local.peek(key)?.let { return it }
        val selected = active()
        if (selected.backend == StorageBackend.TELEGRAM && selected.isReady()) {
            telegram.peek(key)?.let { return it }
            return telegram.materialize(key)
        }
        return null
    }

    /** True when a read for [key] would need the network. */
    suspend fun requiresNetwork(key: StorageKey): Boolean =
        local.peek(key) == null && telegram.peek(key) == null

    /**
     * Deletes [key] from every backend that holds it, as a soft delete. Used when
     * the user deletes a patient image: leaving an orphan in the drive would let
     * a later "restore from Telegram" resurrect a record they removed on purpose.
     */
    suspend fun deleteEverywhere(key: StorageKey) {
        runCatching { local.delete(key) }
            .onFailure { DriveLog.w(TAG, "Local soft delete failed for ${key.fileName}", it) }
        if (driveRepository.current().setupCompleted) {
            runCatching { telegram.delete(key) }
                .onFailure { DriveLog.w(TAG, "Drive soft delete failed for ${key.fileName}", it) }
        }
    }

    companion object {
        private const val TAG = "StorageRegistry"
    }
}
