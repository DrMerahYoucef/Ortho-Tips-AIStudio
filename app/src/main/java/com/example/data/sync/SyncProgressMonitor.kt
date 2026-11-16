package com.example.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Active phase of a Telegram Drive sync run. */
enum class SyncPhase {
    IDLE,
    CONNECTING,
    PULLING_MANIFEST,
    SYNCING_DELETIONS,
    RESTORING_PATIENTS,
    PLANNING_MEDIA,
    BACKING_UP_PATIENTS,
    COMPLETED,
    FAILED
}

enum class ExplanationSeverity {
    INFO,
    WARNING,
    ERROR
}

enum class ExplanationCode {
    AUTO_RESTORED_PATIENTS,
    EMPTY_PATIENT_OVERWRITE_BLOCKED,
    NO_PATIENT_SNAPSHOT_FOUND,
    UNREADABLE_ENCRYPTED_OBJECTS,
    DRIVE_NOT_CONNECTED,
    DRIVE_NOT_SELECTED,
    MANIFEST_PULL_FAILED,
    DELETION_SYNC_FAILED,
    PLANNING_FAILED,
    SNAPSHOT_RESTORE_FAILED,
    SNAPSHOT_PUSH_FAILED,
    TRANSFER_FAILURES,
    TRANSFERS_WAITING_WIFI,
    TRANSFERS_WAITING_CHARGING,
    TRANSFERS_OFFLINE,
    TRANSFERS_SESSION_CONNECTING,
    TRANSFERS_RETRY_BACKOFF,
    TRANSFERS_PAUSED_QUEUE,
    GENERIC_ERROR
}

data class SyncExplanation(
    val code: ExplanationCode,
    val severity: ExplanationSeverity,
    val detail: String? = null
)

data class SyncLiveStatus(
    val isSyncing: Boolean = false,
    val isTransferring: Boolean = false,
    val currentTransferKey: String? = null,
    val currentTransferFraction: Float = 0f,
    val nextRetryAt: Long = 0L,
    val phase: SyncPhase = SyncPhase.IDLE,
    val scope: SyncScope? = null,
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    val manifestRowsSeen: Int = 0,
    val unreadableObjects: Int = 0,
    val localPatientCount: Int = 0,
    val remotePatientSnapshotsCount: Int = 0,
    val restoredPatientCount: Int = 0,
    val backedUpPatientCount: Int = 0,
    val uploadsQueued: Int = 0,
    val downloadsQueued: Int = 0,
    val snapshotPulled: Boolean = false,
    val snapshotPushed: Boolean = false,
    val errors: List<String> = emptyList(),
    val explanations: List<SyncExplanation> = emptyList(),
    val dismissed: Boolean = false,
    val hasRunSinceLaunch: Boolean = false
)

/**
 * Shared observable status for Telegram Drive sync runs, consumed by both the
 * Telegram Drive Settings screen and the Patients tab banner.
 */
class SyncProgressMonitor {

    private val _status = MutableStateFlow(SyncLiveStatus())
    val status: StateFlow<SyncLiveStatus> = _status.asStateFlow()

    fun onSyncStarted(scope: SyncScope, localPatientCount: Int) {
        _status.update { current ->
            SyncLiveStatus(
                isSyncing = true,
                isTransferring = current.isTransferring,
                currentTransferKey = current.currentTransferKey,
                currentTransferFraction = current.currentTransferFraction,
                phase = SyncPhase.CONNECTING,
                scope = scope,
                startedAt = System.currentTimeMillis(),
                localPatientCount = localPatientCount,
                dismissed = false,
                hasRunSinceLaunch = true
            )
        }
    }

    fun onTransferBatchStarted() {
        _status.update { current ->
            current.copy(
                isTransferring = true,
                currentTransferKey = null,
                currentTransferFraction = 0f,
                nextRetryAt = 0L,
                hasRunSinceLaunch = true
            )
        }
    }

    fun onTransferItemProgress(storageKey: String, fraction: Float) {
        _status.update { current ->
            current.copy(
                isTransferring = true,
                currentTransferKey = storageKey,
                currentTransferFraction = fraction.coerceIn(0f, 1f)
            )
        }
    }

    fun onTransferBatchFinished(nextRetryAt: Long = 0L) {
        _status.update { current ->
            current.copy(
                isTransferring = false,
                currentTransferKey = null,
                currentTransferFraction = 0f,
                nextRetryAt = nextRetryAt
            )
        }
    }

    fun updatePhase(phase: SyncPhase) {
        _status.update { it.copy(phase = phase) }
    }

    fun onManifestPulled(seen: Int, unreadable: Int, remotePatientSnapshots: Int) {
        _status.update { current ->
            val updatedExplanations = if (unreadable > 0) {
                current.explanations.addUnique(
                    SyncExplanation(
                        code = ExplanationCode.UNREADABLE_ENCRYPTED_OBJECTS,
                        severity = ExplanationSeverity.WARNING,
                        detail = unreadable.toString()
                    )
                )
            } else current.explanations
            current.copy(
                manifestRowsSeen = seen,
                unreadableObjects = unreadable,
                remotePatientSnapshotsCount = remotePatientSnapshots,
                explanations = updatedExplanations
            )
        }
    }

    fun onPatientsRestored(patientCount: Int, localTotalAfter: Int) {
        _status.update { current ->
            current.copy(
                restoredPatientCount = patientCount,
                localPatientCount = localTotalAfter,
                snapshotPulled = true,
                explanations = current.explanations.addUnique(
                    SyncExplanation(
                        code = ExplanationCode.AUTO_RESTORED_PATIENTS,
                        severity = ExplanationSeverity.INFO,
                        detail = patientCount.toString()
                    )
                )
            )
        }
    }

    fun onPatientsBackedUp(patientCount: Int) {
        _status.update { current ->
            current.copy(
                backedUpPatientCount = patientCount,
                snapshotPushed = true
            )
        }
    }

    fun addExplanation(explanation: SyncExplanation) {
        _status.update { current ->
            current.copy(explanations = current.explanations.addUnique(explanation))
        }
    }

    fun onMediaPlanned(uploads: Int, downloads: Int) {
        _status.update { it.copy(uploadsQueued = uploads, downloadsQueued = downloads) }
    }

    fun onSyncFinished(report: SyncReport, localPatientCount: Int) {
        _status.update { current ->
            val finishedExplanations = current.explanations.toMutableList()
            if (report.errors.isNotEmpty() && finishedExplanations.none { it.severity == ExplanationSeverity.ERROR }) {
                finishedExplanations.add(
                    SyncExplanation(
                        code = ExplanationCode.GENERIC_ERROR,
                        severity = ExplanationSeverity.ERROR,
                        detail = report.errors.firstOrNull()
                    )
                )
            }
            current.copy(
                isSyncing = false,
                phase = if (report.succeeded) SyncPhase.COMPLETED else SyncPhase.FAILED,
                finishedAt = report.finishedAt,
                manifestRowsSeen = report.manifestRowsSeen,
                unreadableObjects = report.unreadableObjects,
                localPatientCount = localPatientCount,
                uploadsQueued = report.uploadsQueued,
                downloadsQueued = report.downloadsQueued,
                snapshotPulled = report.snapshotPulled,
                snapshotPushed = report.snapshotPushed,
                errors = report.errors,
                explanations = finishedExplanations,
                hasRunSinceLaunch = true
            )
        }
    }

    fun dismiss() {
        _status.update { it.copy(dismissed = true) }
    }

    fun reopen() {
        _status.update { it.copy(dismissed = false) }
    }

    private fun List<SyncExplanation>.addUnique(item: SyncExplanation): List<SyncExplanation> =
        if (any { it.code == item.code && it.detail == item.detail }) this else this + item
}
