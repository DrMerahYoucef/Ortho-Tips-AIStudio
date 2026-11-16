package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.RemoteObjectEntity
import com.example.data.local.entity.SyncTombstoneEntity
import com.example.data.local.entity.TransferQueueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RemoteObjectDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RemoteObjectEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<RemoteObjectEntity>)

    @Query("SELECT * FROM remote_objects WHERE storageKey = :storageKey")
    suspend fun get(storageKey: String): RemoteObjectEntity?

    @Query("SELECT * FROM remote_objects WHERE uniqueFileId = :uniqueFileId LIMIT 1")
    suspend fun getByUniqueFileId(uniqueFileId: String): RemoteObjectEntity?

    @Query("SELECT * FROM remote_objects WHERE deletedAt IS NULL ORDER BY modifiedAt DESC")
    suspend fun getAllLive(): List<RemoteObjectEntity>

    @Query("SELECT * FROM remote_objects WHERE deletedAt IS NOT NULL")
    suspend fun getAllDeleted(): List<RemoteObjectEntity>

    @Query(
        "SELECT * FROM remote_objects WHERE storageKey LIKE :prefix || '%' " +
                "AND deletedAt IS NULL ORDER BY storageKey"
    )
    suspend fun getByPrefix(prefix: String): List<RemoteObjectEntity>

    /** Duplicate detection: an identical hash means the bytes are already up. */
    @Query("SELECT * FROM remote_objects WHERE contentHash = :hash AND deletedAt IS NULL LIMIT 1")
    suspend fun findByHash(hash: String): RemoteObjectEntity?

    @Query("SELECT COUNT(*) FROM remote_objects WHERE deletedAt IS NULL")
    fun observeLiveCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM remote_objects WHERE deletedAt IS NULL")
    suspend fun totalLiveBytes(): Long

    @Query("SELECT * FROM remote_objects WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun getPurgeable(cutoff: Long): List<RemoteObjectEntity>

    @Query("UPDATE remote_objects SET deletedAt = :deletedAt WHERE storageKey = :storageKey")
    suspend fun markDeleted(storageKey: String, deletedAt: Long)

    @Query("UPDATE remote_objects SET deletedAt = NULL WHERE storageKey = :storageKey")
    suspend fun restore(storageKey: String)

    @Query("DELETE FROM remote_objects WHERE storageKey = :storageKey")
    suspend fun hardDelete(storageKey: String)

    /** Used by "rebuild manifest": wipe the index, then replay the channel. */
    @Query("DELETE FROM remote_objects")
    suspend fun clear()
}

@Dao
interface TransferQueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TransferQueueEntity): Long

    @Update
    suspend fun update(entity: TransferQueueEntity)

    @Query("SELECT * FROM transfer_queue WHERE id = :id")
    suspend fun get(id: Long): TransferQueueEntity?

    @Query(
        "SELECT * FROM transfer_queue WHERE state IN ('PENDING', 'RUNNING') " +
                "AND nextAttemptAt <= :now ORDER BY createdAt ASC LIMIT :limit"
    )
    suspend fun claimBatch(now: Long, limit: Int): List<TransferQueueEntity>

    @Query(
        "SELECT * FROM transfer_queue WHERE storageKey = :storageKey " +
                "AND state IN ('PENDING', 'RUNNING') LIMIT 1"
    )
    suspend fun findActiveFor(storageKey: String): TransferQueueEntity?

    /** When the earliest waiting transfer becomes due; null when nothing waits. */
    @Query("SELECT MIN(nextAttemptAt) FROM transfer_queue WHERE state = 'PENDING'")
    suspend fun nextPendingAt(): Long?

    @Query("SELECT COUNT(*) FROM transfer_queue WHERE direction = :direction AND state = 'PENDING'")
    fun observePendingCount(direction: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM transfer_queue WHERE state = 'FAILED'")
    fun observeFailedCount(): Flow<Int>

    @Query("SELECT * FROM transfer_queue ORDER BY updatedAt DESC LIMIT :limit")
    fun observeHistory(limit: Int): Flow<List<TransferQueueEntity>>

    @Query(
        "SELECT * FROM transfer_queue WHERE state IN ('RUNNING', 'PENDING') " +
                "ORDER BY CASE WHEN state = 'RUNNING' THEN 0 ELSE 1 END, createdAt ASC LIMIT :limit"
    )
    fun observeActiveTransfers(limit: Int = 20): Flow<List<TransferQueueEntity>>

    @Query("SELECT * FROM transfer_queue WHERE state = 'FAILED'")
    suspend fun getFailed(): List<TransferQueueEntity>

    @Query(
        "UPDATE transfer_queue SET state = 'PENDING', attemptCount = 0, nextAttemptAt = 0, " +
                "lastError = NULL, updatedAt = :now WHERE state = 'FAILED'"
    )
    suspend fun retryAllFailed(now: Long)

    /** Clears scheduled backoff delays on pending transfers so a manual kick runs immediately. */
    @Query(
        "UPDATE transfer_queue SET nextAttemptAt = 0, updatedAt = :now " +
                "WHERE state = 'PENDING' AND nextAttemptAt > :now"
    )
    suspend fun resetPendingBackoff(now: Long = System.currentTimeMillis())

    /** Recovers rows left RUNNING by a process death so they are picked up again. */
    @Query(
        "UPDATE transfer_queue SET state = 'PENDING', updatedAt = :now " +
                "WHERE state = 'RUNNING'"
    )
    suspend fun requeueInterrupted(now: Long)

    @Query("DELETE FROM transfer_queue WHERE state = 'SUCCEEDED' AND updatedAt < :cutoff")
    suspend fun trimHistory(cutoff: Long): Int

    @Query(
        "UPDATE transfer_queue SET state = 'CANCELLED', updatedAt = :now " +
                "WHERE storageKey = :storageKey AND state IN ('PENDING', 'RUNNING')"
    )
    suspend fun cancelActiveFor(storageKey: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM transfer_queue WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SyncTombstoneDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SyncTombstoneEntity)

    @Query("SELECT * FROM sync_tombstones WHERE propagated = 0")
    suspend fun getPending(): List<SyncTombstoneEntity>

    @Query("SELECT * FROM sync_tombstones WHERE storageKey = :storageKey")
    suspend fun get(storageKey: String): SyncTombstoneEntity?

    @Query("UPDATE sync_tombstones SET propagated = 1 WHERE storageKey = :storageKey")
    suspend fun markPropagated(storageKey: String)

    @Query("DELETE FROM sync_tombstones WHERE storageKey = :storageKey")
    suspend fun delete(storageKey: String)

    @Query("DELETE FROM sync_tombstones WHERE propagated = 1 AND deletedAt < :cutoff")
    suspend fun trim(cutoff: Long): Int
}
