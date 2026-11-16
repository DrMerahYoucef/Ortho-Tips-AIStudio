package com.example.ui.screens.storage

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.telegram.DriveLog
import com.example.utils.MediaActions
import com.example.viewmodel.TelegramDriveSettingsViewModel
import com.example.R
import androidx.compose.ui.res.stringResource

/**
 * Developer diagnostics.
 *
 * Deliberately read-only apart from clearing the log buffer: when a surgeon is
 * chasing a sync problem in a clinic, the last thing the screen should offer is
 * a button that changes state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageDiagnosticsScreen(
    viewModel: TelegramDriveSettingsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.storage_diagnostics_drive_diagnostics)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        MediaActions.copyToClipboard(context, "Ortho Tips drive log", DriveLog.dump())
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.storage_diagnostics_copy_logs))
                    }
                    IconButton(onClick = viewModel::clearLogs) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.storage_diagnostics_clear_logs))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                DriveSection("BACKEND") {
                    DriveInfoRow("Selected backend", state.settings.backend.name)
                    DriveInfoRow("Setup completed", state.settings.setupCompleted.toString())
                    DriveInfoRow(
                        "Drive channel",
                        state.settings.chatId?.toString() ?: "not initialised"
                    )
                    DriveInfoRow("Telegram auth", state.authState::class.simpleName ?: "unknown")
                    DriveInfoRow("Connection", state.connection.name)
                    DriveInfoRow(
                        "Content key fingerprint",
                        state.keyFingerprint ?: "no key on this device"
                    )
                }
            }

            item {
                DriveSection("STORAGE HEALTH") {
                    state.localUsage?.let {
                        DriveInfoRow(
                            "Local",
                            "${it.objectCount} files, ${formatBytes(it.usedBytes)} used, " +
                                    "${formatBytes(it.availableBytes)} free"
                        )
                    }
                    state.driveUsage?.let {
                        DriveInfoRow(
                            "Drive index",
                            "${it.objectCount} objects, ${formatBytes(it.usedBytes)} tracked"
                        )
                    }
                    DriveInfoRow(
                        "Queue",
                        "${state.pendingUploads} up, ${state.pendingDownloads} down, " +
                                "${state.failedTransfers} failed"
                    )
                    state.settings.lastSyncError.takeIf { it.isNotBlank() }?.let {
                        DriveInfoRow("Last sync error", it)
                    }
                }
            }

            item {
                DriveSection("INTEGRITY") {
                    val report = state.integrity
                    if (report == null) {
                        DriveActionRow(
                            icon = Icons.Default.VerifiedUser,
                            title = stringResource(R.string.storage_diagnostics_run_an_integrity_check),
                            subtitle = stringResource(R.string.storage_diagnostics_verifies_every_indexed_object_against),
                            onClick = viewModel::verifyIntegrity
                        )
                    } else {
                        DriveInfoRow("Objects checked", report.checked.toString())
                        DriveInfoRow(
                            "Missing in Telegram",
                            if (report.missingRemotely.isEmpty()) "none"
                            else report.missingRemotely.take(5).joinToString(", ") {
                                it.substringAfterLast('/')
                            } + if (report.missingRemotely.size > 5) " and more" else ""
                        )
                        DriveInfoRow(
                            "Hash mismatches",
                            if (report.hashMismatches.isEmpty()) "none"
                            else report.hashMismatches.size.toString()
                        )
                        DriveInfoRow("Orphaned transfers", report.orphanedTransfers.toString())
                        DriveActionRow(
                            icon = Icons.Default.VerifiedUser,
                            title = stringResource(R.string.storage_diagnostics_run_again),
                            subtitle = stringResource(R.string.storage_diagnostics_re_checks_every_object),
                            onClick = viewModel::verifyIntegrity
                        )
                    }
                }
            }

            item {
                state.lastReport?.let { report ->
                    DriveSection("LAST SYNC RUN") {
                        DriveInfoRow("Scope", report.scope.name)
                        DriveInfoRow("Duration", "${report.durationMillis} ms")
                        DriveInfoRow("Manifest entries seen", report.manifestRowsSeen.toString())
                        if (report.unreadableObjects > 0) {
                            DriveInfoRow(
                                "Unreadable in the drive",
                                "${report.unreadableObjects} objects sealed with a key that " +
                                        "is not on this device"
                            )
                        }
                        DriveInfoRow("Queued", "${report.uploadsQueued} up / ${report.downloadsQueued} down")
                        DriveInfoRow("Deletions propagated", report.tombstonesPropagated.toString())
                        DriveInfoRow("Record snapshot pushed", report.snapshotPushed.toString())
                        if (report.conflicts.isNotEmpty()) {
                            DriveInfoRow("Unresolved conflicts", report.conflicts.size.toString())
                        }
                        if (report.errors.isNotEmpty()) {
                            DriveInfoRow("Errors", report.errors.joinToString("; "))
                        }
                    }
                }
            }

            item {
                Text(
                    text = "EVENT LOG (${logs.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }

            items(logs.asReversed()) { entry ->
                Text(
                    text = entry.format(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = when (entry.level) {
                        DriveLog.Level.ERROR -> MaterialTheme.colorScheme.error
                        DriveLog.Level.WARN -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
