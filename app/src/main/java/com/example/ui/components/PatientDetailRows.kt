package com.example.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontStyle
import com.example.data.local.entity.Attendance
import com.example.ui.theme.OrthoColors
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.entity.SurgeryEntity
import com.example.data.local.entity.TimelineEventEntity
import com.example.utils.DateFormat

/** RegionBadge is already defined in Common.kt (same package) and reused as-is here. */

@Composable
fun TimelineEventRow(
    event: TimelineEventEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Same rule as the Agenda: attendance only counts once the appointment's day has come.
    val startOfTomorrow = java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.DAY_OF_YEAR, 1)
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val attendance = if (event.eventDate >= startOfTomorrow) Attendance.UNKNOWN
    else runCatching { Attendance.valueOf(event.attendance) }.getOrDefault(Attendance.UNKNOWN)
    RecordCard(onClick = onClick, modifier = modifier) {
        Column(modifier = Modifier.weight(1f)) {
            // Date with the time, and whether the patient came, as recorded from the
            // Agenda or the widget.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = DateFormat.formatDateTime(event.eventDate),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f, fill = false)
                )
                AttendanceBadge(attendance)
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = event.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (event.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = event.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (event.remark.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Default.EditNote,
                        contentDescription = stringResource(R.string.note_remark),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp).size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = event.remark,
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        RowOverflowMenu(
            deleteLabel = stringResource(R.string.patient_detail_rows_delete_event),
            onDelete = onDelete
        )
    }
}

@Composable
fun SurgeryRow(
    surgery: SurgeryEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onGenerateProtocol: () -> Unit,
    modifier: Modifier = Modifier
) {
    RecordCard(onClick = onClick, modifier = modifier) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = DateFormat.formatDate(surgery.surgeryDate),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = surgery.procedure.ifBlank { stringResource(R.string.surgery_untitled_procedure) },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (surgery.diagnosis.isNotBlank()) {
                Text(
                    text = surgery.diagnosis,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (surgery.hospitalClinic.isNotBlank() || surgery.surgeon.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = listOf(surgery.surgeon, surgery.hospitalClinic).filter { it.isNotBlank() }.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // The protocol is the everyday action, so it stays one tap away;
        // deleting moves into the menu, away from a stray thumb.
        IconButton(onClick = onGenerateProtocol) {
            Icon(
                Icons.Default.PictureAsPdf,
                contentDescription = stringResource(R.string.patient_detail_rows_generate_operative_protocol),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        RowOverflowMenu(
            deleteLabel = stringResource(R.string.patient_detail_rows_delete_surgery),
            onDelete = onDelete
        )
    }
}

/** Flat, tappable card shared by the timeline and surgery lists. */
@Composable
private fun RecordCard(
    onClick: () -> Unit,
    modifier: Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
private fun RowOverflowMenu(deleteLabel: String, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.patient_detail_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(deleteLabel, color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    expanded = false
                    onDelete()
                }
            )
        }
    }
}

/** "Came" in green or "Did not come" in red; nothing while unrecorded. */
@Composable
private fun AttendanceBadge(attendance: Attendance) {
    if (attendance == Attendance.UNKNOWN) return
    val attended = attendance == Attendance.ATTENDED
    Spacer(modifier = Modifier.width(8.dp))
    Surface(
        color = if (attended) OrthoColors.attendedContainer else MaterialTheme.colorScheme.errorContainer,
        contentColor = if (attended) OrthoColors.onAttendedContainer else MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(50)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Icon(
                if (attended) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                stringResource(if (attended) R.string.note_attended else R.string.note_missed),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}
