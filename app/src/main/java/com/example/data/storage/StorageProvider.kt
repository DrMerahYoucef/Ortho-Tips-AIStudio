package com.example.data.storage

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * The single abstraction repositories depend on instead of concrete storage.
 *
 * Contract every implementation must honour:
 *  - [materialize] always returns a readable local `File` or null. Callers that
 *    hand a path to the PDF generator, the annotation canvas or Coil use this,
 *    so the rest of the app keeps working with plain files and never learns
 *    whether the bytes came off disk or off Telegram.
 *  - [delete] is a soft delete. Bytes are only removed by an explicit
 *    [purge] call, which the sync engine issues after a retention window.
 *    Nothing in the UI may call [purge] as part of a routine flow.
 *  - Methods are safe to call when the backend is not ready; they fail with
 *    [StorageException] rather than blocking the caller indefinitely.
 */
interface StorageProvider {

    val backend: StorageBackend

    /** True when the backend can serve reads and writes right now. */
    suspend fun isReady(): Boolean

    /**
     * Stores [source] under [key]. The source file is left untouched; providers
     * that need their own copy make one.
     */
    fun put(
        key: StorageKey,
        source: File,
        kind: StorageObjectKind,
        mimeType: String
    ): Flow<TransferProgress>

    /** Fetches [key] into [destination], overwriting it on success. */
    fun get(key: StorageKey, destination: File): Flow<TransferProgress>

    /**
     * Returns a local file holding the bytes for [key], fetching them if needed.
     * Null when the object does not exist or cannot be retrieved offline.
     */
    suspend fun materialize(key: StorageKey): File?

    /** Local file for [key] if it is already present, without any network work. */
    suspend fun peek(key: StorageKey): File?

    suspend fun exists(key: StorageKey): Boolean

    suspend fun stat(key: StorageKey): StorageObject?

    suspend fun list(prefix: String): List<StorageObject>

    /** Marks [key] deleted. Bytes survive until [purge]. */
    suspend fun delete(key: StorageKey)

    /** Permanently removes soft-deleted objects older than [olderThanMillis]. */
    suspend fun purge(olderThanMillis: Long): Int

    /** Recomputes the stored hash and compares it against the manifest. */
    suspend fun verify(key: StorageKey): Boolean

    suspend fun usage(): StorageUsage

    /** Drops cached bytes that can be re-fetched. Never affects canonical data. */
    suspend fun clearCache(): Long
}

/**
 * Waits for a transfer flow's terminal event and returns it.
 *
 * Deliberately not `first { it is Completed || it is Failed }`. `first` stops
 * collection by throwing `AbortFlowException`, which lands in the provider's own
 * `catch (e: Exception)` and makes it emit after the flow has been torn down —
 * an illegal emission that kotlinx rejects as a flow-transparency violation.
 * These flows end on their own right after the terminal event, so ordinary
 * collection is both correct and cheaper than an abort.
 */
suspend fun Flow<TransferProgress>.awaitTerminal(): TransferProgress? {
    var terminal: TransferProgress? = null
    collect { progress ->
        if (progress is TransferProgress.Completed || progress is TransferProgress.Failed) {
            terminal = progress
        }
    }
    return terminal
}
