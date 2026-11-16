package com.example.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.example.OrthoApplication
import com.example.data.local.entity.TransferDirection
import com.example.data.local.entity.TransferQueueEntity
import com.example.data.local.entity.TransferState
import com.example.data.prefs.CachePolicy
import com.example.data.prefs.SyncFrequency
import com.example.data.storage.StorageBackend
import com.example.data.storage.StorageKey
import com.example.data.storage.StorageObjectKind
import com.example.data.storage.TransferProgress
import com.example.data.sync.SyncScope
import com.example.data.telegram.DriveLog
import com.example.data.telegram.TelegramAuthState
import com.example.di.AppModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

private fun Context.appModule(): AppModule =
    (applicationContext as OrthoApplication).appModule

/**
 * Drains `transfer_queue`, one row at a time, until the queue is empty or the
 * batch limit is reached.
 *
 * Work is claimed from the database rather than passed in as input data, which is
 * what makes an interrupted transfer resumable: WorkManager can kill this worker
 * mid-upload and the row is still PENDING with its attempt count intact.
 */
class TransferQueueWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val module = applicationContext.appModule()
        val queueDao = module.database.transferQueueDao()
        val telegram = module.telegramStorageProvider
        val local = module.localStorageProvider

        // Signed out or back on local storage: nothing to drain, and no reason to
        // keep waking up.
        val driveSettings = module.telegramDriveRepository.current()
        if (!driveSettings.setupCompleted) return Result.success()

        // A background start is a cold process: the Telegram session is still
        // being restored by OrthoApplication when this runs. Wait for it rather
        // than retrying at once — WorkManager's retry backoff doubles up to five
        // hours, and while this unique work sat in that backoff every new
        // "kick" (KEEP policy) was ignored, so uploads stayed pending for hours.
        if (!telegram.isReady()) {
            withTimeoutOrNull(READY_WAIT_MILLIS) {
                module.telegramClient.authState.first { it == TelegramAuthState.Ready }
            }
        }
        if (!telegram.isReady()) {
            DriveLog.d(TAG, "Drive not ready; checking again shortly")
            SyncScheduler(applicationContext).scheduleWakeUp(NOT_READY_RETRY_MILLIS)
            return Result.success()
        }

        queueDao.requeueInterrupted(System.currentTimeMillis())
        DriveLog.i(TAG, "Transfer worker started (attempt ${runAttemptCount + 1})")
        var processed = 0
        var failures = 0

        while (processed < BATCH_LIMIT) {
            val batch = queueDao.claimBatch(System.currentTimeMillis(), 1)
            val row = batch.firstOrNull() ?: break
            queueDao.update(
                row.copy(state = TransferState.RUNNING.name, updatedAt = System.currentTimeMillis())
            )

            val key = runCatching { StorageKey(row.storageKey) }.getOrNull()
            if (key == null) {
                queueDao.update(row.fail("Malformed storage key"))
                processed++
                failures++
                continue
            }

            val kind = runCatching { StorageObjectKind.valueOf(row.kind) }
                .getOrDefault(StorageObjectKind.OTHER)
            val file = File(row.localPath)
            val direction = runCatching { TransferDirection.valueOf(row.direction) }
                .getOrDefault(TransferDirection.UPLOAD)

            val flow = when (direction) {
                TransferDirection.UPLOAD -> {
                    if (!file.exists()) {
                        // The source is gone: the record was deleted after queueing.
                        queueDao.delete(row.id)
                        processed++
                        continue
                    }
                    telegram.put(key, file, kind, row.mimeType)
                }

                TransferDirection.DOWNLOAD -> telegram.get(key, file)
            }

            var terminal: TransferProgress? = null
            try {
                flow.collect { progress ->
                    when (progress) {
                        is TransferProgress.Running -> setProgress(
                            workDataOf(
                                PROGRESS_KEY to row.storageKey,
                                PROGRESS_FRACTION to progress.fraction
                            )
                        )

                        is TransferProgress.Completed, is TransferProgress.Failed -> terminal = progress
                        else -> Unit
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A throw inside the transfer (drive not initialised, network
                // drop, TDLib error) used to crash the whole worker and leave the
                // row RUNNING. Treat it as a retryable failure of this row only.
                DriveLog.w(TAG, "Transfer of ${key.fileName} threw", error)
                terminal = TransferProgress.Failed(error.message ?: "Transfer error", retryable = true)
            }

            when (val outcome = terminal) {
                is TransferProgress.Completed -> {
                    queueDao.update(
                        row.copy(
                            state = TransferState.SUCCEEDED.name,
                            bytesTransferred = outcome.obj.sizeBytes,
                            totalBytes = outcome.obj.sizeBytes,
                            lastError = null,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    // A download lands in the local tree with the remote modifiedAt timestamp,
                    // so the next incremental sync recognises both sides already match.
                    if (direction == TransferDirection.DOWNLOAD) {
                        if (outcome.obj.modifiedAt > 0) file.setLastModified(outcome.obj.modifiedAt)
                        DriveLog.d(TAG, "Fetched ${key.fileName} into local storage")
                    } else if (
                        driveSettings.cachePolicy == CachePolicy.MINIMAL &&
                        !kind.isThumbnail &&
                        key.value.startsWith(StorageKey.PATIENTS_PREFIX) &&
                        !file.name.endsWith(".original.jpg") &&
                        module.patientMediaGateway.isBackedUp(file.absolutePath)
                    ) {
                        if (file.delete()) {
                            DriveLog.d(TAG, "Offloaded ${key.fileName} under MINIMAL cache policy")
                        }
                    }
                }

                is TransferProgress.Failed -> {
                    failures++
                    if (outcome.retryable && row.attemptCount + 1 < MAX_ATTEMPTS) {
                        queueDao.update(row.backOff(outcome.reason))
                    } else {
                        queueDao.update(row.fail(outcome.reason))
                    }
                }

                else -> queueDao.update(row.backOff("Transfer produced no result"))
            }
            processed++
        }

        if (processed > 0) DriveLog.i(TAG, "Transfer batch: $processed handled, $failures failed")

        // Rows backed off after a failure are only due later. Nothing else wakes
        // them, so this worker books its own next pass for the moment the
        // earliest one is due (or straight away if the batch limit cut it short).
        // Result.retry() is avoided: its growing backoff is what parked uploads.
        val nextDue = queueDao.nextPendingAt()
        if (nextDue != null) {
            val delay = (nextDue - System.currentTimeMillis()).coerceAtLeast(MIN_WAKE_UP_MILLIS)
            SyncScheduler(applicationContext).scheduleWakeUp(delay)
        }
        return Result.success()
    }

    /** Exponential backoff capped so a flood-wait never parks work for hours. */
    private fun TransferQueueEntity.backOff(reason: String): TransferQueueEntity {
        val attempt = attemptCount + 1
        val delayMillis = min(
            BASE_BACKOFF_MILLIS * 2.0.pow(attempt.toDouble()).toLong(),
            MAX_BACKOFF_MILLIS
        )
        return copy(
            state = TransferState.PENDING.name,
            attemptCount = attempt,
            nextAttemptAt = System.currentTimeMillis() + delayMillis,
            lastError = reason,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun TransferQueueEntity.fail(reason: String): TransferQueueEntity = copy(
        state = TransferState.FAILED.name,
        attemptCount = attemptCount + 1,
        lastError = reason,
        updatedAt = System.currentTimeMillis()
    )

    companion object {
        private const val TAG = "TransferQueueWorker"
        const val UNIQUE_NAME = "ortho_drive_transfers"
        const val PROGRESS_KEY = "storage_key"
        const val PROGRESS_FRACTION = "fraction"
        private const val BATCH_LIMIT = 40
        private const val READY_WAIT_MILLIS = 90_000L
        private const val NOT_READY_RETRY_MILLIS = 60_000L
        private const val MIN_WAKE_UP_MILLIS = 15_000L
        private const val MAX_ATTEMPTS = 6
        private const val BASE_BACKOFF_MILLIS = 15_000L
        private const val MAX_BACKOFF_MILLIS = 30 * 60 * 1000L
    }
}

/**
 * Fires when backed-off transfers become due and restarts the queue worker
 * with the user's current Wi-Fi / charging preferences.
 */
class TransferWakeUpWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = applicationContext.appModule().telegramDriveRepository.current()
        if (!settings.setupCompleted) return Result.success()
        SyncScheduler(applicationContext).kickTransfers(
            settings.syncOnWifiOnly, settings.syncWhileChargingOnly
        )
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "ortho_drive_transfers_wake_up"
    }
}

/**
 * Plans a sync run, then hands execution to [TransferQueueWorker]. Kept separate
 * so a periodic sync that finds nothing to do costs one cheap pass rather than
 * holding a worker open for the duration of every transfer.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val module = applicationContext.appModule()
        val settings = module.telegramDriveRepository.current()
        if (!settings.setupCompleted) return Result.success()
        DriveLog.d(TAG, "Sync worker started")

        if (settings.backend == StorageBackend.TELEGRAM && !module.telegramStorageProvider.isReady()) {
            withTimeoutOrNull(READY_WAIT_MILLIS) {
                module.telegramClient.authState.first { it == TelegramAuthState.Ready }
            }
        }

        val scope = if (inputData.getBoolean(INPUT_FULL, false)) {
            SyncScope.FULL
        } else {
            SyncScope.INCREMENTAL
        }

        val report = module.syncEngine.sync(scope)

        // Always make sure the queue is being drained. Rows queued earlier are not
        // counted in uploadsQueued (they are already active), and a sync with an
        // error — e.g. one unreadable object in the channel — used to skip the
        // kick entirely, stranding every pending upload.
        SyncScheduler(applicationContext).kickTransfers(
            settings.syncOnWifiOnly, settings.syncWhileChargingOnly
        )

        if (!report.succeeded) {
            DriveLog.w(TAG, "Sync reported ${report.errors.size} errors")
            return if (report.hasRetryableFailure) Result.retry() else Result.success()
        }

        module.syncEngine.cleanUp(CLEANUP_RETENTION_MILLIS)
        return Result.success()
    }

    companion object {
        private const val TAG = "SyncWorker"
        const val UNIQUE_PERIODIC = "ortho_drive_periodic_sync"
        const val UNIQUE_ONE_SHOT = "ortho_drive_sync_now"
        const val INPUT_FULL = "full_sync"
        private const val READY_WAIT_MILLIS = 90_000L
        private const val CLEANUP_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}

/**
 * Single place that translates the user's sync preferences into WorkManager
 * constraints, so "Wi-Fi only" and "while charging only" mean the same thing on
 * every path that schedules work.
 */
class SyncScheduler(private val context: Context) {

    private val workManager get() = WorkManager.getInstance(context)

    suspend fun applyPreferences() {
        val settings = context.appModule().telegramDriveRepository.settings.first()
        val period = settings.syncFrequency.periodHours
        if (!settings.setupCompleted || !settings.backgroundSyncEnabled || period == null) {
            // MANUAL and ON_CHANGE need no periodic worker: the first waits for the
            // surgeon, the second is driven by saves as they happen.
            cancelPeriodic()
            return
        }
        schedulePeriodic(
            periodHours = period,
            wifiOnly = settings.syncOnWifiOnly,
            chargingOnly = settings.syncWhileChargingOnly
        )
    }

    fun schedulePeriodic(periodHours: Long, wifiOnly: Boolean, chargingOnly: Boolean) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            periodHours.coerceAtLeast(MIN_INTERVAL_HOURS), TimeUnit.HOURS
        )
            .setConstraints(constraints(wifiOnly, chargingOnly))
            .build()
        workManager.enqueueUniquePeriodicWork(
            SyncWorker.UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelPeriodic() {
        workManager.cancelUniqueWork(SyncWorker.UNIQUE_PERIODIC)
    }

    /** Runs a sync straight away, ignoring the Wi-Fi and charging preferences. */
    fun syncNow(full: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setInputData(workDataOf(SyncWorker.INPUT_FULL to full))
            .build()
        workManager.enqueueUniqueWork(
            SyncWorker.UNIQUE_ONE_SHOT,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /**
     * Pushes a record snapshot shortly after the database changes.
     *
     * Delayed and REPLACE-policy on purpose: typing a patient's details fires a
     * save per field, and without the debounce that would upload a snapshot per
     * keystroke. Each new call pushes the timer out, so a burst of edits settles
     * into one upload.
     */
    fun scheduleRecordSnapshot(wifiOnly: Boolean = false, chargingOnly: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(wifiOnly, chargingOnly))
            .setInitialDelay(RECORD_DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            UNIQUE_RECORD_SNAPSHOT,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /**
     * Makes sure the queue worker runs now.
     *
     * The old KEEP policy kept whatever was already enqueued — including a job
     * parked in WorkManager's retry backoff, which grows to five hours and is
     * saved across app updates. Every kick was then silently ignored, which is
     * why uploads sat at "en attente". Now a waiting job is replaced; only a
     * worker that is actually running is left alone, with a fresh run chained
     * after it so rows queued meanwhile are not missed.
     */
    suspend fun kickTransfers(wifiOnly: Boolean = false, chargingOnly: Boolean = false) {
        val running = runCatching {
            workManager.getWorkInfosForUniqueWork(TransferQueueWorker.UNIQUE_NAME).await()
        }.getOrDefault(emptyList()).any { it.state == WorkInfo.State.RUNNING }

        val request = OneTimeWorkRequestBuilder<TransferQueueWorker>()
            .setConstraints(constraints(wifiOnly, chargingOnly))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            TransferQueueWorker.UNIQUE_NAME,
            if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
            request
        )
        DriveLog.d(
            TAG,
            "Transfers kicked (" + (if (running) "after the running pass" else "starting now") +
                    ", wifiOnly=$wifiOnly, chargingOnly=$chargingOnly)"
        )
    }

    /**
     * Books a future pass of the queue worker. Uses its own unique name so it
     * never replaces (and so cancels) a transfer in progress; when it fires it
     * simply kicks the real worker.
     */
    fun scheduleWakeUp(delayMillis: Long) {
        val request = OneTimeWorkRequestBuilder<TransferWakeUpWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(
            TransferWakeUpWorker.UNIQUE_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /** Restarts the queue worker after constraint-relevant settings change. */
    fun restartTransfers(wifiOnly: Boolean, chargingOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<TransferQueueWorker>()
            .setConstraints(constraints(wifiOnly, chargingOnly))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            TransferQueueWorker.UNIQUE_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(SyncWorker.UNIQUE_PERIODIC)
        workManager.cancelUniqueWork(SyncWorker.UNIQUE_ONE_SHOT)
        workManager.cancelUniqueWork(TransferQueueWorker.UNIQUE_NAME)
        workManager.cancelUniqueWork(TransferWakeUpWorker.UNIQUE_NAME)
        workManager.cancelUniqueWork(UNIQUE_RECORD_SNAPSHOT)
    }

    private fun constraints(wifiOnly: Boolean, chargingOnly: Boolean): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(chargingOnly)
            // No "storage not low" constraint: on a phone Android considers low
            // on space it held every upload back forever, without a single log
            // line. A genuinely full disk now fails the transfer visibly instead.
            .build()

    companion object {
        private const val TAG = "SyncScheduler"

        /** WorkManager's floor for a periodic request. */
        private const val MIN_INTERVAL_HOURS = 1L
        private const val UNIQUE_RECORD_SNAPSHOT = "ortho_drive_record_snapshot"
        private const val RECORD_DEBOUNCE_SECONDS = 20L
    }
}
