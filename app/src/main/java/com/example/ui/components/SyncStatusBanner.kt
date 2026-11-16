package com.example.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.OrthoApplication
import com.example.R
import com.example.data.local.entity.TransferDirection
import com.example.data.local.entity.TransferQueueEntity
import com.example.data.local.entity.TransferState
import com.example.data.prefs.TelegramDriveSettings
import com.example.data.storage.StorageObjectKind
import com.example.data.sync.ExplanationCode
import com.example.data.sync.ExplanationSeverity
import com.example.data.sync.SyncExplanation
import com.example.data.sync.SyncLiveStatus
import com.example.data.sync.SyncPhase
import com.example.data.sync.SyncScope
import com.example.data.telegram.DriveLog
import com.example.utils.MediaActions
import com.example.work.SyncScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shared expandable synchronization banner shown on both Telegram Drive Settings
 * and the Patients tab. Displays live sync phase, patient & media transfer progress,
 * plain-language error/warning explanations, recent sync logs, and a Copy Logs button.
 * Once a sync finishes, it stays visible with its summary until explicitly dismissed.
 */
@Composable
fun SyncStatusBanner(
    modifier: Modifier = Modifier,
    onNavigateToSettings: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val appModule = remember(context) {
        (context.applicationContext as? OrthoApplication)?.appModule
    } ?: return

    val live by appModule.syncProgressMonitor.status.collectAsStateWithLifecycle()
    val driveSettings by appModule.telegramDriveRepository.settings
        .collectAsStateWithLifecycle(initialValue = TelegramDriveSettings())
    val totalPatients by appModule.database.patientDao().observePatientCount()
        .collectAsStateWithLifecycle(initialValue = 0)
    val activePatients by appModule.database.patientDao().observeActivePatientCount()
        .collectAsStateWithLifecycle(initialValue = 0)
    val pendingUploads by appModule.database.transferQueueDao()
        .observePendingCount(TransferDirection.UPLOAD.name)
        .collectAsStateWithLifecycle(initialValue = 0)
    val pendingDownloads by appModule.database.transferQueueDao()
        .observePendingCount(TransferDirection.DOWNLOAD.name)
        .collectAsStateWithLifecycle(initialValue = 0)
    val failedTransfers by appModule.database.transferQueueDao().observeFailedCount()
        .collectAsStateWithLifecycle(initialValue = 0)
    val activeTransfers by appModule.database.transferQueueDao().observeActiveTransfers(10)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val remoteObjectsCount by appModule.database.remoteObjectDao().observeLiveCount()
        .collectAsStateWithLifecycle(initialValue = 0)
    val logs by DriveLog.entries.collectAsStateWithLifecycle()

    val hasQueueWork = pendingUploads > 0 || pendingDownloads > 0 ||
        activeTransfers.any { it.state == TransferState.RUNNING.name }
    val hasActiveWork = live.isSyncing || hasQueueWork
    val hasPersistentIssue = failedTransfers > 0 || driveSettings.lastSyncError.isNotBlank()
    val shouldShow = (driveSettings.setupCompleted || live.isSyncing || live.hasRunSinceLaunch) &&
        (hasActiveWork || ((live.hasRunSinceLaunch || hasPersistentIssue) && !live.dismissed))

    if (!shouldShow) return

    var expanded by rememberSaveable { mutableStateOf(false) }
    var copiedJustNow by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(copiedJustNow) {
        if (copiedJustNow) {
            delay(2_000)
            copiedJustNow = false
        }
    }

    val resolvedExplanations = remember(live, failedTransfers, driveSettings.lastSyncError) {
        buildResolvedExplanations(live, failedTransfers, driveSettings.lastSyncError)
    }

    val hasError = live.phase == SyncPhase.FAILED ||
        live.errors.isNotEmpty() ||
        failedTransfers > 0 ||
        driveSettings.lastSyncError.isNotBlank()
    val hasWarning = !hasError && resolvedExplanations.any { it.severity == ExplanationSeverity.WARNING }

    val containerColor = when {
        hasActiveWork -> MaterialTheme.colorScheme.secondaryContainer
        hasError -> MaterialTheme.colorScheme.errorContainer
        hasWarning -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = when {
        hasActiveWork -> MaterialTheme.colorScheme.onSecondaryContainer
        hasError -> MaterialTheme.colorScheme.onErrorContainer
        hasWarning -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    val phaseTitle = syncPhaseTitle(live.phase, hasQueueWork, hasError, hasWarning)
    val mediaSummary = buildString {
        append(stringResource(R.string.sync_banner_media_summary, pendingUploads, pendingDownloads))
        if (failedTransfers > 0) {
            append(" · ")
            append(stringResource(R.string.sync_banner_media_failed_suffix, failedTransfers))
        }
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.14f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("sync_status_banner")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header row (always visible)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)
            ) {
                if (hasActiveWork) {
                    CircularProgressIndicator(
                        color = contentColor,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Icon(
                        imageVector = when {
                            hasError -> Icons.Default.ErrorOutline
                            hasWarning -> Icons.Default.WarningAmber
                            else -> Icons.Default.CheckCircle
                        },
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = phaseTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(
                            R.string.sync_banner_patients_summary,
                            totalPatients,
                            activePatients
                        ) + " · " + mediaSummary,
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.testTag("sync_banner_toggle")
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(
                            if (expanded) R.string.sync_banner_collapse else R.string.sync_banner_expand
                        ),
                        tint = contentColor
                    )
                }

                if (!hasActiveWork) {
                    IconButton(
                        onClick = { appModule.syncProgressMonitor.dismiss() },
                        modifier = Modifier.testTag("sync_banner_dismiss")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.sync_banner_dismiss),
                            tint = contentColor
                        )
                    }
                }
            }

            if (hasActiveWork) {
                val runningItem = activeTransfers.firstOrNull {
                    it.state == TransferState.RUNNING.name && it.totalBytes > 0
                }
                if (runningItem != null && !live.isSyncing) {
                    LinearProgressIndicator(
                        progress = {
                            (runningItem.bytesTransferred.toFloat() / runningItem.totalBytes).coerceIn(0f, 1f)
                        },
                        color = contentColor,
                        trackColor = contentColor.copy(alpha = 0.2f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                            .padding(bottom = 10.dp)
                    )
                } else {
                    LinearProgressIndicator(
                        color = contentColor,
                        trackColor = contentColor.copy(alpha = 0.2f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                            .padding(bottom = 10.dp)
                    )
                }
            }

            // Expandable detail area
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.92f))
                        .padding(14.dp)
                ) {
                    // 1. Patients Section
                    BannerDetailSection(
                        icon = Icons.Default.People,
                        title = stringResource(R.string.sync_banner_sec_patients)
                    ) {
                        val patientOutcome = when {
                            live.snapshotPulled && live.restoredPatientCount > 0 -> stringResource(
                                R.string.sync_banner_patients_restored,
                                live.restoredPatientCount
                            )
                            live.snapshotPushed -> stringResource(
                                R.string.sync_banner_patients_backed_up,
                                live.backedUpPatientCount
                            )
                            else -> stringResource(R.string.sync_banner_patients_in_sync)
                        }
                        Text(
                            text = patientOutcome,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(
                                R.string.sync_banner_patients_summary,
                                totalPatients,
                                activePatients
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.sync_banner_scope_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 2. Media Transfers Section
                    BannerDetailSection(
                        icon = Icons.Default.PermMedia,
                        title = stringResource(R.string.sync_banner_sec_media)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.sync_banner_media_queue_detail,
                                pendingUploads,
                                pendingDownloads,
                                failedTransfers,
                                remoteObjectsCount
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (activeTransfers.isEmpty() && failedTransfers == 0) {
                            Text(
                                text = stringResource(R.string.sync_banner_media_idle),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            activeTransfers.take(4).forEach { row ->
                                ActiveTransferItemRow(row)
                            }
                        }
                        if (live.finishedAt > live.startedAt && !live.isSyncing) {
                            Text(
                                text = stringResource(
                                    R.string.sync_banner_duration_manifest,
                                    (live.finishedAt - live.startedAt),
                                    live.manifestRowsSeen
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 3. Errors & Explanations Section
                    BannerDetailSection(
                        icon = if (hasError) Icons.Default.ErrorOutline else Icons.Default.WarningAmber,
                        title = stringResource(R.string.sync_banner_sec_explanations),
                        tint = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    ) {
                        if (resolvedExplanations.isEmpty()) {
                            Text(
                                text = stringResource(R.string.sync_banner_no_errors),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                resolvedExplanations.forEach { exp ->
                                    ExplanationItemCard(exp)
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 4. Sync Logs + Copy Logs Button
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.sync_banner_sec_logs, logs.size),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            FilledTonalButton(
                                onClick = {
                                    val report = buildSyncDiagnosticReport(
                                        context = context,
                                        live = live,
                                        totalPatients = totalPatients,
                                        pendingUploads = pendingUploads,
                                        pendingDownloads = pendingDownloads,
                                        failedTransfers = failedTransfers,
                                        explanations = resolvedExplanations,
                                        logsDump = DriveLog.dump()
                                    )
                                    MediaActions.copyToClipboard(
                                        context,
                                        "Ortho Tips sync logs",
                                        report
                                    )
                                    copiedJustNow = true
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("sync_banner_copy_logs")
                            ) {
                                Icon(
                                    imageVector = if (copiedJustNow) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(
                                        if (copiedJustNow) R.string.sync_banner_logs_copied else R.string.sync_banner_copy_logs
                                    ),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 72.dp, max = 180.dp)
                                .testTag("sync_banner_logs")
                        ) {
                            if (logs.isEmpty()) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(14.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.sync_banner_logs_empty),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                val displayedLogs = remember(logs) { logs.takeLast(80).asReversed() }
                                LazyColumn(
                                    contentPadding = PaddingValues(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(displayedLogs) { entry ->
                                        Text(
                                            text = entry.format(),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = when (entry.level) {
                                                DriveLog.Level.ERROR -> MaterialTheme.colorScheme.error
                                                DriveLog.Level.WARN -> MaterialTheme.colorScheme.tertiary
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = Modifier.horizontalScroll(rememberScrollState())
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 5. Contextual Actions Row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    appModule.syncEngine.sync(SyncScope.INCREMENTAL)
                                    val settings = appModule.telegramDriveRepository.current()
                                    SyncScheduler(context.applicationContext).kickTransfers(
                                        settings.syncOnWifiOnly,
                                        settings.syncWhileChargingOnly
                                    )
                                }
                            },
                            enabled = !live.isSyncing,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.sync_banner_sync_now))
                        }

                        if (failedTransfers > 0) {
                            FilledTonalButton(
                                onClick = {
                                    scope.launch {
                                        appModule.database.transferQueueDao()
                                            .retryAllFailed(System.currentTimeMillis())
                                        val settings = appModule.telegramDriveRepository.current()
                                        SyncScheduler(context.applicationContext).kickTransfers(
                                            settings.syncOnWifiOnly,
                                            settings.syncWhileChargingOnly
                                        )
                                    }
                                },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.sync_banner_retry_failed))
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        if (onNavigateToSettings != null) {
                            TextButton(
                                onClick = onNavigateToSettings,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.sync_banner_open_settings))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BannerDetailSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = tint
            )
        }
        content()
    }
}

@Composable
private fun ActiveTransferItemRow(row: TransferQueueEntity) {
    val kindLabel = stringResource(
        when (row.kind) {
            StorageObjectKind.PATIENT_IMAGE.name -> R.string.transfer_kind_photo
            StorageObjectKind.PATIENT_THUMBNAIL.name -> R.string.transfer_kind_thumbnail
            StorageObjectKind.PATIENT_DOCUMENT.name -> R.string.transfer_kind_document
            StorageObjectKind.DATABASE_SNAPSHOT.name -> R.string.transfer_kind_records
            else -> R.string.transfer_kind_file
        }
    )
    val directionLabel = stringResource(
        if (row.direction == TransferDirection.UPLOAD.name) R.string.transfer_direction_upload
        else R.string.transfer_direction_download
    )
    val stateLabel = stringResource(
        when (row.state) {
            TransferState.RUNNING.name -> R.string.transfer_state_running
            TransferState.FAILED.name -> R.string.transfer_state_failed
            else -> R.string.transfer_state_pending
        }
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.CloudSync,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$kindLabel · ${row.storageKey.substringAfterLast('/').take(10)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$directionLabel • $stateLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (row.state == TransferState.RUNNING.name && row.totalBytes > 0) {
            LinearProgressIndicator(
                progress = { (row.bytesTransferred.toFloat() / row.totalBytes).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun ExplanationItemCard(explanation: SyncExplanation) {
    val bgColor = when (explanation.severity) {
        ExplanationSeverity.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
        ExplanationSeverity.WARNING -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
        ExplanationSeverity.INFO -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    }
    val fgColor = when (explanation.severity) {
        ExplanationSeverity.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        ExplanationSeverity.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
        ExplanationSeverity.INFO -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    val (title, body) = explanationText(explanation)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = fgColor
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = fgColor.copy(alpha = 0.9f)
            )
        }
    }
}

@Composable
private fun syncPhaseTitle(
    phase: SyncPhase,
    hasQueueWork: Boolean,
    hasError: Boolean,
    hasWarning: Boolean
): String = when {
    phase == SyncPhase.CONNECTING -> stringResource(R.string.sync_phase_connecting)
    phase == SyncPhase.PULLING_MANIFEST -> stringResource(R.string.sync_phase_manifest)
    phase == SyncPhase.SYNCING_DELETIONS -> stringResource(R.string.sync_phase_deletions)
    phase == SyncPhase.RESTORING_PATIENTS -> stringResource(R.string.sync_phase_restoring)
    phase == SyncPhase.PLANNING_MEDIA -> stringResource(R.string.sync_phase_planning)
    phase == SyncPhase.BACKING_UP_PATIENTS -> stringResource(R.string.sync_phase_backing_up)
    hasQueueWork -> stringResource(R.string.sync_phase_transferring)
    hasError -> stringResource(R.string.sync_phase_failed)
    hasWarning -> stringResource(R.string.sync_phase_warning)
    else -> stringResource(R.string.sync_phase_completed)
}

@Composable
private fun explanationText(explanation: SyncExplanation): Pair<String, String> {
    val detail = explanation.detail.orEmpty()
    return when (explanation.code) {
        ExplanationCode.AUTO_RESTORED_PATIENTS ->
            stringResource(R.string.sync_exp_auto_restored_title) to
                stringResource(R.string.sync_exp_auto_restored_body, detail.ifBlank { "0" })

        ExplanationCode.EMPTY_PATIENT_OVERWRITE_BLOCKED ->
            stringResource(R.string.sync_exp_empty_blocked_title) to
                stringResource(R.string.sync_exp_empty_blocked_body)

        ExplanationCode.NO_PATIENT_SNAPSHOT_FOUND ->
            stringResource(R.string.sync_exp_no_snapshot_title) to
                stringResource(R.string.sync_exp_no_snapshot_body)

        ExplanationCode.UNREADABLE_ENCRYPTED_OBJECTS ->
            stringResource(R.string.sync_exp_unreadable_title, detail.ifBlank { "?" }) to
                stringResource(R.string.sync_exp_unreadable_body, detail.ifBlank { "?" })

        ExplanationCode.DRIVE_NOT_CONNECTED ->
            stringResource(R.string.sync_exp_disconnected_title) to
                stringResource(R.string.sync_exp_disconnected_body)

        ExplanationCode.DRIVE_NOT_SELECTED ->
            stringResource(R.string.sync_exp_not_selected_title) to
                stringResource(R.string.sync_exp_not_selected_body)

        ExplanationCode.TRANSFER_FAILURES -> {
            val count = detail.substringBefore('|').toIntOrNull() ?: 1
            val lastErr = detail.substringAfter('|', "").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            stringResource(R.string.sync_exp_transfer_failed_title, count) to
                stringResource(R.string.sync_exp_transfer_failed_body, count, lastErr)
        }

        ExplanationCode.MANIFEST_PULL_FAILED,
        ExplanationCode.DELETION_SYNC_FAILED,
        ExplanationCode.PLANNING_FAILED,
        ExplanationCode.SNAPSHOT_RESTORE_FAILED,
        ExplanationCode.SNAPSHOT_PUSH_FAILED,
        ExplanationCode.GENERIC_ERROR ->
            stringResource(R.string.sync_exp_step_failed_title) to
                stringResource(R.string.sync_exp_step_failed_body, detail.ifBlank { "Unknown error" })
    }
}

internal fun buildResolvedExplanations(
    live: SyncLiveStatus,
    failedTransfers: Int,
    lastSyncError: String
): List<SyncExplanation> {
    val result = live.explanations.toMutableList()
    if (failedTransfers > 0 && result.none { it.code == ExplanationCode.TRANSFER_FAILURES }) {
        result += SyncExplanation(
            code = ExplanationCode.TRANSFER_FAILURES,
            severity = ExplanationSeverity.ERROR,
            detail = failedTransfers.toString()
        )
    }
    if (lastSyncError.isNotBlank() && result.none { it.severity == ExplanationSeverity.ERROR }) {
        result += SyncExplanation(
            code = ExplanationCode.GENERIC_ERROR,
            severity = ExplanationSeverity.ERROR,
            detail = lastSyncError
        )
    }
    return result
}

internal fun buildSyncDiagnosticReport(
    context: Context,
    live: SyncLiveStatus,
    totalPatients: Int,
    pendingUploads: Int,
    pendingDownloads: Int,
    failedTransfers: Int,
    explanations: List<SyncExplanation>,
    logsDump: String
): String = buildString {
    appendLine("=== Ortho Tips Sync Report ===")
    appendLine("Phase: ${live.phase}")
    appendLine("Scope: ${live.scope?.name ?: "N/A"} (patients & patient media only)")
    appendLine("Local patients: $totalPatients")
    appendLine("Manifest entries seen: ${live.manifestRowsSeen} (unreadable: ${live.unreadableObjects})")
    appendLine("Snapshot pulled: ${live.snapshotPulled} (restored=${live.restoredPatientCount}), pushed: ${live.snapshotPushed} (backedUp=${live.backedUpPatientCount})")
    appendLine("Media queue: $pendingUploads uploads, $pendingDownloads downloads, $failedTransfers failed")
    if (explanations.isNotEmpty()) {
        appendLine()
        appendLine("--- Explanations ---")
        explanations.forEach { exp ->
            appendLine("[${exp.severity}] ${exp.code}: ${exp.detail.orEmpty()}")
        }
    }
    if (live.errors.isNotEmpty()) {
        appendLine()
        appendLine("--- Errors ---")
        live.errors.forEach { appendLine("- $it") }
    }
    appendLine()
    appendLine("--- Drive Logs ---")
    appendLine(logsDump.ifBlank { "(empty)" })
}
