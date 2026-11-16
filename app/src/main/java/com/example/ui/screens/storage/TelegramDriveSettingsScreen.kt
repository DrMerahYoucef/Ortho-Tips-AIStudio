package com.example.ui.screens.storage

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.TransferDirection
import com.example.data.local.entity.TransferState
import com.example.data.storage.StorageObjectKind
import com.example.data.prefs.CachePolicy
import com.example.data.prefs.SyncFrequency
import com.example.data.storage.StorageBackend
import com.example.data.telegram.TelegramAuthState
import com.example.data.telegram.TelegramConnectionState
import com.example.viewmodel.DriveSettingsUiState
import com.example.viewmodel.TelegramDriveSettingsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.R
import androidx.compose.ui.res.stringResource

/**
 * The Telegram Drive section of Settings.
 *
 * Grouped so the destructive-looking actions sit apart from the everyday ones,
 * and every action that could be read as "this might delete my patients" says
 * plainly in its subtitle what it does not touch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramDriveSettingsScreen(
    viewModel: TelegramDriveSettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSetup: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmAction by remember { mutableStateOf<ConfirmAction?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    confirmAction?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(action.title) },
            text = { Text(action.body, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                Button(onClick = {
                    action.onConfirm()
                    confirmAction = null
                }) { Text(action.confirmLabel) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.drive_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item { ConnectionCard(state) }

                item { com.example.ui.components.SyncStatusBanner() }

                item {
                    DriveSection(stringResource(R.string.drive_sec_backup)) {
                        // Not a choice of where data lives any more. Patients and
                        // media are always written to this device; the drive is an
                        // encrypted second copy you can lean on to free space.
                        DriveToggleRow(
                            title = stringResource(R.string.drive_backup_toggle),
                            subtitle = if (state.settings.setupCompleted) {
stringResource(R.string.drive_backup_on_subtitle)
                            } else {
                                stringResource(R.string.drive_backup_off_subtitle)
                            },
                            checked = state.settings.backend == StorageBackend.TELEGRAM,
                            onChange = { enabled ->
                                viewModel.selectBackend(
                                    if (enabled) StorageBackend.TELEGRAM else StorageBackend.LOCAL
                                )
                            }
                        )
                        if (!state.settings.setupCompleted) {
                            DriveActionRow(
                                icon = Icons.Default.CloudUpload,
                                title = stringResource(R.string.drive_run_setup),
                                subtitle = stringResource(R.string.drive_run_setup_subtitle),
                                onClick = onNavigateToSetup
                            )
                        }
                    }
                }

                if (state.settings.setupCompleted) {
                    item { DriveSection(stringResource(R.string.drive_sec_account)) { AccountRows(state, viewModel, onNavigateToSetup) { confirmAction = it } } }
                    item {
                        DriveSection(stringResource(R.string.passphrase_section_header)) {
                            PassphraseRows(
                                ui = state.passphrase,
                                onSave = viewModel::savePassphrase,
                                onUnlock = viewModel::unlockDrive,
                                onRecheck = viewModel::refreshPassphrase,
                                onDialogClosed = viewModel::clearPassphraseOutcome
                            )
                        }
                    }
                    item { DriveSection(stringResource(R.string.drive_sec_usage)) { UsageRows(state) } }
                    item { DriveSection(stringResource(R.string.drive_sec_sync)) { SyncRows(state, viewModel) } }
                    item { DriveSection(stringResource(R.string.drive_sec_transfers)) { TransferRows(state, viewModel) } }
                    item { DriveSection(stringResource(R.string.drive_sec_cache)) { CacheRows(state, viewModel) } }
                    item { DriveSection(stringResource(R.string.drive_sec_migration)) { MigrationRows(viewModel) { confirmAction = it } } }
                    item { DriveSection(stringResource(R.string.drive_sec_maintenance)) { MaintenanceRows(viewModel, onNavigateToDiagnostics) { confirmAction = it } } }
                    item { TransferHistoryCard(state) }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }

            state.busyLabel?.let { label ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private data class ConfirmAction(
    val title: String,
    val body: String,
    val confirmLabel: String,
    val onConfirm: () -> Unit
)

@Composable
private fun ConnectionCard(state: DriveSettingsUiState) {
    val connected = state.authState == TelegramAuthState.Ready
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (connected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(18.dp)
        ) {
            Icon(
                imageVector = if (connected) Icons.Default.CloudDone else Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        !state.settings.setupCompleted -> stringResource(R.string.drive_status_not_set_up)
                        connected -> stringResource(R.string.drive_status_connected)
                        state.authState is TelegramAuthState.Failed ->
                            (state.authState as TelegramAuthState.Failed).message
                        else -> stringResource(R.string.drive_status_connecting)
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = buildString {
                        if (state.settings.accountName.isNotBlank()) {
                            append(state.settings.accountName)
                            if (state.settings.accountPhoneSuffix.isNotBlank()) {
                                append(" (•••${state.settings.accountPhoneSuffix})")
                            }
                            append(" • ")
                        }
                        append(
                            when (state.connection) {
                                TelegramConnectionState.READY -> stringResource(R.string.drive_net_ready)
                                TelegramConnectionState.UPDATING -> stringResource(R.string.drive_net_updating)
                                TelegramConnectionState.CONNECTING -> stringResource(R.string.drive_net_connecting)
                                TelegramConnectionState.WAITING_FOR_NETWORK -> stringResource(R.string.drive_net_offline)
                            }
                        )
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                if (state.settings.lastSyncAt > 0) {
                    Text(
                        text = stringResource(R.string.drive_last_sync, formatTime(state.settings.lastSyncAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountRows(
    state: DriveSettingsUiState,
    viewModel: TelegramDriveSettingsViewModel,
    onNavigateToSetup: () -> Unit,
    onConfirm: (ConfirmAction) -> Unit
) {
    DriveActionRow(
        icon = Icons.Default.Refresh,
        title = stringResource(R.string.drive_reconnect),
        subtitle = stringResource(R.string.drive_reconnect_subtitle),
        onClick = viewModel::reconnect
    )
    DriveActionRow(
        icon = Icons.Default.CloudUpload,
        title = stringResource(R.string.drive_rerun_setup),
        subtitle = stringResource(R.string.drive_rerun_setup_subtitle),
        onClick = onNavigateToSetup
    )
    state.keyFingerprint?.let {
        DriveInfoRow(stringResource(R.string.passphrase_key_row_title), stringResource(R.string.passphrase_key_row_value, it))
    }
    val signOutTitle = stringResource(R.string.telegram_drive_settings_sign_out_of_telegram)
    val signOutBody = stringResource(R.string.drive_sign_out_body)
    val signOutLabel = stringResource(R.string.telegram_drive_settings_sign_out)
    DriveActionRow(
        icon = Icons.Default.Logout,
        title = stringResource(R.string.drive_sign_out),
        subtitle = stringResource(R.string.drive_sign_out_subtitle),
        onClick = {
            onConfirm(
                ConfirmAction(
                    title = signOutTitle,
                    body = signOutBody,
                    confirmLabel = signOutLabel,
                    onConfirm = viewModel::logout
                )
            )
        }
    )
}

@Composable
private fun UsageRows(state: DriveSettingsUiState) {
    state.localUsage?.let {
        DriveInfoRow(
            stringResource(R.string.drive_local_storage),
            stringResource(R.string.drive_local_usage, formatBytes(it.usedBytes), it.objectCount, formatBytes(it.availableBytes))
        )
    }
    state.driveUsage?.let {
        DriveInfoRow(
            stringResource(R.string.drive_title),
            stringResource(R.string.drive_remote_usage, formatBytes(it.usedBytes), it.objectCount)
        )
    }
}

@Composable
private fun SyncRows(state: DriveSettingsUiState, viewModel: TelegramDriveSettingsViewModel) {
    var frequencyMenuOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(14.dp)) {
        Text(stringResource(R.string.drive_frequency_title), style = MaterialTheme.typography.titleSmall)
        Text(
            text = stringResource(R.string.drive_frequency_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { frequencyMenuOpen = true }
                    .padding(vertical = 12.dp, horizontal = 4.dp)
            ) {
                Text(
                    text = state.settings.syncFrequency.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.telegram_drive_settings_change_frequency))
            }
            DropdownMenu(
                expanded = frequencyMenuOpen,
                onDismissRequest = { frequencyMenuOpen = false }
            ) {
                SyncFrequency.entries.forEach { frequency ->
                    DropdownMenuItem(
                        text = { Text(frequency.label) },
                        onClick = {
                            frequencyMenuOpen = false
                            viewModel.setSyncFrequency(frequency)
                        },
                        leadingIcon = {
                            if (state.settings.syncFrequency == frequency) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null)
                            }
                        }
                    )
                }
            }
        }
    }
    DriveToggleRow(
        title = stringResource(R.string.drive_background_sync),
        subtitle = stringResource(R.string.drive_background_sync_subtitle),
        checked = state.settings.backgroundSyncEnabled,
        onChange = viewModel::setBackgroundSync
    )
    DriveToggleRow(
        title = stringResource(R.string.drive_wifi_only),
        subtitle = stringResource(R.string.drive_wifi_only_subtitle),
        checked = state.settings.syncOnWifiOnly,
        onChange = viewModel::setWifiOnly
    )
    DriveToggleRow(
        title = stringResource(R.string.drive_charging_only),
        subtitle = stringResource(R.string.drive_charging_only_subtitle),
        checked = state.settings.syncWhileChargingOnly,
        onChange = viewModel::setChargingOnly
    )
    DriveToggleRow(
        title = stringResource(R.string.drive_encrypt_uploads),
        subtitle = stringResource(R.string.drive_encrypt_uploads_subtitle),
        checked = state.settings.encryptUploads,
        onChange = viewModel::setEncryptUploads
    )
    DriveToggleRow(
        title = stringResource(R.string.drive_version_history),
        subtitle = stringResource(R.string.drive_version_history_subtitle),
        checked = state.settings.keepVersionHistory,
        onChange = viewModel::setKeepVersionHistory
    )
    DriveActionRow(
        icon = Icons.Default.Sync,
        title = stringResource(R.string.drive_sync_now),
        subtitle = stringResource(R.string.drive_sync_now_subtitle),
        onClick = { viewModel.syncNow(full = false) }
    )
    DriveActionRow(
        icon = Icons.Default.Sync,
        title = stringResource(R.string.drive_full_sync),
        subtitle = stringResource(R.string.drive_full_sync_subtitle),
        onClick = { viewModel.syncNow(full = true) }
    )
}

@Composable
private fun TransferRows(state: DriveSettingsUiState, viewModel: TelegramDriveSettingsViewModel) {
    DriveInfoRow(stringResource(R.string.drive_pending_uploads), stringResource(R.string.drive_waiting_count, state.pendingUploads))
    DriveInfoRow(stringResource(R.string.drive_pending_downloads), stringResource(R.string.drive_waiting_count, state.pendingDownloads))
    if (state.failedTransfers > 0) {
        DriveActionRow(
            icon = Icons.Default.Refresh,
            title = stringResource(R.string.drive_retry_failed),
            subtitle = stringResource(R.string.drive_failed_requeue, state.failedTransfers),
            onClick = viewModel::retryFailedTransfers
        )
    }
}

@Composable
private fun CacheRows(state: DriveSettingsUiState, viewModel: TelegramDriveSettingsViewModel) {
    Column(modifier = Modifier.padding(14.dp)) {
        Text(stringResource(R.string.drive_cache_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CachePolicy.entries.forEach { policy ->
                FilterChip(
                    selected = state.settings.cachePolicy == policy,
                    onClick = { viewModel.setCachePolicy(policy) },
                    label = {
                        Text(
                            when (policy) {
                                CachePolicy.KEEP_EVERYTHING -> stringResource(R.string.drive_cache_everything)
                                CachePolicy.HYBRID -> stringResource(R.string.drive_cache_recent)
                                CachePolicy.MINIMAL -> stringResource(R.string.drive_cache_minimal)
                            }
                        )
                    }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.drive_cache_limit, state.settings.cacheBudgetMb),
            style = MaterialTheme.typography.bodySmall
        )
        Slider(
            value = state.settings.cacheBudgetMb.toFloat(),
            onValueChange = { viewModel.setCacheBudgetMb(it.toInt()) },
            valueRange = 128f..4096f,
            steps = 30
        )
    }
    DriveActionRow(
        icon = Icons.Default.DeleteSweep,
        title = stringResource(R.string.drive_clear_cache),
        subtitle = stringResource(R.string.drive_clear_cache_subtitle),
        onClick = viewModel::clearCache
    )
}

@Composable
private fun MigrationRows(
    viewModel: TelegramDriveSettingsViewModel,
    onConfirm: (ConfirmAction) -> Unit
) {
    val copyTitle = stringResource(R.string.telegram_drive_settings_copy_everything_to_telegram_drive)
    val copyBody = stringResource(R.string.drive_copy_up_body)
    val copyLabel = stringResource(R.string.telegram_drive_settings_start)
    val restoreTitle = stringResource(R.string.telegram_drive_settings_restore_from_telegram_drive)
    val restoreBody = stringResource(R.string.drive_restore_body)
    val restoreLabel = stringResource(R.string.action_restore)
    DriveActionRow(
        icon = Icons.Default.CloudUpload,
        title = stringResource(R.string.drive_copy_up),
        subtitle = stringResource(R.string.drive_copy_up_subtitle),
        onClick = {
            onConfirm(
                ConfirmAction(
                    title = copyTitle,
                    body = copyBody,
                    confirmLabel = copyLabel,
                    onConfirm = viewModel::migrateLocalToDrive
                )
            )
        }
    )
    DriveActionRow(
        icon = Icons.Default.Download,
        title = stringResource(R.string.drive_restore_down),
        subtitle = stringResource(R.string.drive_restore_down_subtitle),
        onClick = {
            onConfirm(
                ConfirmAction(
                    title = restoreTitle,
                    body = restoreBody,
                    confirmLabel = restoreLabel,
                    onConfirm = viewModel::migrateDriveToLocal
                )
            )
        }
    )
}

@Composable
private fun MaintenanceRows(
    viewModel: TelegramDriveSettingsViewModel,
    onNavigateToDiagnostics: () -> Unit,
    onConfirm: (ConfirmAction) -> Unit
) {
    val rebuildTitle = stringResource(R.string.telegram_drive_settings_rebuild_the_manifest)
    val rebuildLabel = stringResource(R.string.telegram_drive_settings_rebuild)
    val rebuildBody = stringResource(R.string.drive_rebuild_body)
    DriveActionRow(
        icon = Icons.Default.VerifiedUser,
        title = stringResource(R.string.drive_verify),
        subtitle = stringResource(R.string.drive_verify_subtitle),
        onClick = viewModel::verifyIntegrity
    )
    DriveActionRow(
        icon = Icons.Default.Restore,
        title = stringResource(R.string.drive_rebuild),
        subtitle = stringResource(R.string.drive_rebuild_subtitle),
        onClick = {
            onConfirm(
                ConfirmAction(
                    title = rebuildTitle,
                    body = rebuildBody,
                    confirmLabel = rebuildLabel,
                    onConfirm = viewModel::rebuildManifest
                )
            )
        }
    )
    DriveActionRow(
        icon = Icons.Default.BugReport,
        title = stringResource(R.string.drive_diagnostics),
        subtitle = stringResource(R.string.drive_diagnostics_subtitle),
        onClick = onNavigateToDiagnostics
    )
}

@Composable
private fun TransferHistoryCard(state: DriveSettingsUiState) {
    if (state.history.isEmpty()) return
    DriveSection(stringResource(R.string.transfer_history_title)) {
        state.history.take(12).forEach { row ->
            // A photo is sent as two objects that share a file name: the full
            // image and its small preview. Saying which one it is stops them
            // looking like duplicates.
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
                    TransferState.PENDING.name -> R.string.transfer_state_pending
                    TransferState.RUNNING.name -> R.string.transfer_state_running
                    TransferState.SUCCEEDED.name -> R.string.transfer_state_done
                    TransferState.FAILED.name -> R.string.transfer_state_failed
                    else -> R.string.transfer_state_cancelled
                }
            )
            val isThumbnail = row.kind == StorageObjectKind.PATIENT_THUMBNAIL.name
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (isThumbnail) 30.dp else 14.dp,
                        end = 14.dp,
                        top = if (isThumbnail) 4.dp else 12.dp,
                        bottom = if (isThumbnail) 12.dp else 4.dp
                    )
            ) {
                Icon(
                    imageVector = when (row.state) {
                        TransferState.SUCCEEDED.name -> Icons.Default.CheckCircle
                        TransferState.FAILED.name -> Icons.Default.CloudOff
                        else -> Icons.Default.Sync
                    },
                    contentDescription = null,
                    tint = if (row.state == TransferState.FAILED.name) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(if (isThumbnail) 14.dp else 18.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$kindLabel · ${row.storageKey.substringAfterLast('/').take(8)}",
                        style = if (isThumbnail) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "$directionLabel • $stateLabel" +
                                (row.lastError?.takeIf { row.state == TransferState.FAILED.name }?.let { " • $it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (row.state == TransferState.RUNNING.name && row.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { row.bytesTransferred.toFloat() / row.totalBytes },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

// ---- Shared row composables -----------------------------------------------

@Composable
internal fun DriveSection(title: String, content: @Composable () -> Unit) {
    Column {
        // Same look as the Settings sections: one visual language across both screens.
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 6.dp, bottom = 8.dp)
                .semantics { heading() }
        )
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column { content() }
        }
    }
}

@Composable
internal fun DriveActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(7.dp).size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DriveToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(14.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun DriveInfoRow(title: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

internal fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(epochMillis))
