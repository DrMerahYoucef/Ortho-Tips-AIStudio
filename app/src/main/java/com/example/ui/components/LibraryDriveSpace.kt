package com.example.ui.components

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import com.example.data.storage.DriveSpaceSummary
import kotlinx.coroutines.launch
import com.example.R
import androidx.compose.ui.res.stringResource

/**
 * Reads how much local space the drive makes reclaimable.
 *
 * [refreshKey] lets a caller re-query after something changes — navigating back
 * to a tab, or finishing an offload — without this becoming a polling loop.
 */
@Composable
private fun rememberReclaimableSpace(refreshKey: Any? = null): DriveSpaceSummary? {
    val context = LocalContext.current
    val repository = remember {
        (context.applicationContext as OrthoApplication).appModule.patientRepository
    }
    var summary by remember { mutableStateOf<DriveSpaceSummary?>(null) }
    LaunchedEffect(refreshKey) {
        summary = runCatching { repository.reclaimableSpace() }.getOrNull()
    }
    return summary
}

/**
 * Banner above the patient list offering to free every backed-up file at once.
 *
 * Renders nothing when the drive is off or nothing is reclaimable, so the list is
 * unchanged for anyone not using the drive.
 */
@Composable
fun LibraryDriveSpaceBanner(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val repository = remember {
        (context.applicationContext as OrthoApplication).appModule.patientRepository
    }

    var refreshKey by remember { mutableStateOf(0) }
    val summary = rememberReclaimableSpace(refreshKey)
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val res = androidx.compose.ui.platform.LocalContext.current.resources

    val current = summary ?: return
    if (!current.hasSomethingToFree && message == null) return

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.library_free_all_title)) },
            text = {
                Text(
                    stringResource(R.string.library_free_all_body, current.patients, formatBytes(current.reclaimableBytes)),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirming = false
                    busy = true
                    scope.launch {
                        // NonCancellable: leaving the screen mid-way must not abort a half-done clean-up.
                        val outcome = runCatching { withContext(NonCancellable) { repository.offloadAllPatients() } }.getOrNull()
                        busy = false
                        message = when {
                            outcome == null -> res.getString(R.string.library_free_failed)
                            outcome.objectsRemoved == 0 -> res.getString(R.string.library_free_nothing)
                            else -> res.getString(
                                R.string.library_freed,
                                formatBytes(outcome.bytesFreed),
                                outcome.objectsRemoved
                            )
                        }
                        refreshKey++
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
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CloudDone,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message ?: stringResource(R.string.library_can_free, formatBytes(current.reclaimableBytes)),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = if (message != null) {
                        stringResource(R.string.library_tap_patient_hint)
                    } else {
                        stringResource(R.string.library_backed_up_summary, current.files, current.patients)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (current.hasSomethingToFree) {
                Spacer(Modifier.width(8.dp))
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = { confirming = true }) { Text(stringResource(R.string.library_free_all)) }
                }
            }
        }
    }
}

/**
 * Wraps a navigation icon with a badge when space can be reclaimed.
 *
 * [refreshKey] should change as the user moves between tabs, so the badge
 * reflects reality shortly after an offload rather than going stale.
 */
@Composable
fun DriveSpaceBadge(
    refreshKey: Any?,
    content: @Composable () -> Unit
) {
    val summary = rememberReclaimableSpace(refreshKey)
    if (summary?.hasSomethingToFree != true) {
        content()
        return
    }
    BadgedBox(
        badge = {
            Badge(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ) { Text(summary.patients.toString()) }
        }
    ) { content() }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
