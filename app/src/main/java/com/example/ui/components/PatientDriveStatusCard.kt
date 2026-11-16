package com.example.ui.components

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.OrthoApplication
import com.example.data.storage.PatientDriveStatus
import kotlinx.coroutines.launch
import com.example.R
import androidx.compose.ui.res.stringResource

/**
 * Shows whether this patient is safely in Telegram Drive, and offers to free the
 * local copies once they are.
 *
 * Reads its own state rather than taking it as a parameter, so adding it to the
 * patient screen is a one-line change and nothing has to be threaded through the
 * navigation graph. It renders nothing at all when the drive is off, which keeps
 * the screen identical for anyone not using it.
 *
 * The wording matters more than usual here. "Free local space" has to read as
 * clearly different from "Delete", because one is reversible and the other is
 * not.
 */
@Composable
fun PatientDriveStatusCard(
    patientId: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember {
        (context.applicationContext as OrthoApplication).appModule.patientRepository
    }

    var status by remember(patientId) { mutableStateOf<PatientDriveStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableStateOf(0) }

    LaunchedEffect(patientId, reloadToken) {
        status = runCatching { repository.driveStatus(patientId) }.getOrNull()
    }

    val current = status ?: return
    if (!current.driveEnabled) return

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.patient_free_space_title)) },
            text = {
                Text(
                    stringResource(R.string.patient_free_space_message),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirming = false
                    busy = true
                    scope.launch {
                        // NonCancellable: leaving the patient mid-way must not abort a half-done clean-up.
                        val outcome = runCatching { withContext(NonCancellable) { repository.offloadPatientMedia(patientId) } }
                            .getOrNull()
                        busy = false
                        result = when {
                            outcome == null -> context.getString(R.string.patient_free_space_failed)
                            outcome.objectsRemoved == 0 -> context.getString(R.string.patient_free_space_nothing)
                            else -> context.getString(R.string.patient_free_space_done, formatSize(outcome.bytesFreed))
                        }
                        reloadToken++
                    }
                }) { Text(stringResource(R.string.action_free_space)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (current.fullyBackedUp) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (current.fullyBackedUp) {
                        Icons.Default.CloudDone
                    } else {
                        Icons.Default.CloudSync
                    },
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (current.fullyBackedUp) {
                            stringResource(R.string.patient_drive_backed_up)
                        } else {
                            stringResource(R.string.patient_drive_backup_in_progress)
                        },
                        style = MaterialTheme.typography.titleSmall
                    )
                    val mediaPart = if (current.totalMedia == 0) {
                        stringResource(R.string.patient_detail_no_media)
                    } else {
                        stringResource(R.string.patient_drive_files_progress, current.backedUpMedia, current.totalMedia)
                    }
                    val recordPart = if (!current.recordsBackedUp) stringResource(R.string.patient_drive_record_not_sent) else null
                    val localPart = if (current.localBytes > 0) {
                        stringResource(R.string.patient_drive_on_this_phone, formatSize(current.localBytes))
                    } else null
                    Text(
                        text = listOfNotNull(mediaPart, recordPart, localPart).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!current.fullyBackedUp && current.totalMedia > 0) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { current.backedUpMedia.toFloat() / current.totalMedia },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            result?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (current.canOffload) {
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    TextButton(onClick = { confirming = true }, enabled = !busy) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.patient_drive_free_on_phone, formatSize(current.offloadableBytes)))
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
