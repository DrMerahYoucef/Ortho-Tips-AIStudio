package com.example.ui.screens.patients

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.entity.Attendance
import com.example.data.local.model.TimelineEventWithPatient
import com.example.ui.components.EmptyStateView
import com.example.ui.theme.OrthoColors
import com.example.viewmodel.AppointmentRange
import com.example.viewmodel.AppointmentsViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Every patient's appointments in one diary, grouped by day, because the day's
 * work is a list of people to see rather than a set of patient files to open.
 *
 * Upcoming counts from the start of today; Past shows the most recent first.
 * The list is a live Room query, so a visit marked from the widget appears here
 * at once, and a visit marked here refreshes the widget.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AppointmentsScreen(
    viewModel: AppointmentsViewModel,
    onOpenPatient: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val locale = LocalConfiguration.current.locales[0]
    val range by viewModel.range.collectAsStateWithLifecycle()
    val appointments by viewModel.appointments.collectAsStateWithLifecycle()
    // Ids only, so an open note box survives rotation.
    var noteEventId by rememberSaveable { mutableStateOf<String?>(null) }
    var notePreset by rememberSaveable { mutableStateOf(Attendance.UNKNOWN) }

    val noteEvent = noteEventId?.let { id -> appointments?.firstOrNull { it.id == id } }
    if (noteEvent != null) {
        AttendanceNoteDialog(
            event = noteEvent,
            preset = notePreset,
            canRecordAttendance = startOfDay(noteEvent.eventDate) <= startOfDay(System.currentTimeMillis()),
            onDismiss = { noteEventId = null },
            onSave = { attendance, remark ->
                viewModel.setAttendance(noteEvent.id, attendance, remark)
                noteEventId = null
            }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        val ranges = listOf(
            AppointmentRange.UPCOMING to stringResource(R.string.appointments_upcoming),
            AppointmentRange.PAST to stringResource(R.string.appointments_past)
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            ranges.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = range == value,
                    onClick = { viewModel.showRange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, ranges.size)
                ) { Text(label, maxLines = 1) }
            }
        }

        val list = appointments
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            list.isEmpty() -> EmptyStateView(
                icon = Icons.Default.CalendarMonth,
                title = stringResource(R.string.appointments_empty_title),
                message = stringResource(R.string.appointments_empty_message)
            )

            else -> {
                val dayFormat = remember(locale) { SimpleDateFormat("EEEE d MMMM yyyy", locale) }
                val timeFormat = remember(locale) { SimpleDateFormat("HH:mm", locale) }
                // groupBy keeps the query's order, so Past stays most-recent-first.
                val grouped = remember(list) { list.groupBy { startOfDay(it.eventDate) } }
                val today = startOfDay(System.currentTimeMillis())

                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    grouped.forEach { (day, events) ->
                        stickyHeader(key = "day_$day") {
                            DayHeader(text = dayHeader(day, dayFormat, locale))
                        }
                        items(events, key = { it.id }) { event ->
                            AppointmentCard(
                                event = event,
                                time = timeFormat.format(Date(event.eventDate)),
                                // Came / did not come only makes sense once the day has come.
                                canRecordAttendance = startOfDay(event.eventDate) <= today,
                                onOpen = { onOpenPatient(event.patientId) },
                                onMark = { preset ->
                                    notePreset = preset
                                    noteEventId = event.id
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Stays pinned while its day scrolls, so the date is always in view. */
@Composable
private fun DayHeader(text: String) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 12.dp, bottom = 4.dp)
                .semantics { heading() }
        )
    }
}

@Composable
private fun AppointmentCard(
    event: TimelineEventWithPatient,
    time: String,
    canRecordAttendance: Boolean,
    onOpen: () -> Unit,
    onMark: (Attendance) -> Unit
) {
    // A future appointment has no attendance yet, even if one was recorded by mistake.
    val attendance = if (!canRecordAttendance) Attendance.UNKNOWN
    else runCatching { Attendance.valueOf(event.attendance) }.getOrDefault(Attendance.UNKNOWN)
    val (container, content) = when (attendance) {
        Attendance.ATTENDED -> OrthoColors.attendedContainer to OrthoColors.onAttendedContainer
        Attendance.MISSED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        Attendance.UNKNOWN -> MaterialTheme.colorScheme.surfaceContainerLow to MaterialTheme.colorScheme.onSurface
    }

    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = event.patientName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (event.title.isNotBlank()) {
                        Text(
                            text = event.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = content.copy(alpha = 0.8f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                AttendanceLabel(attendance)
            }

            val note = event.remark.ifBlank { event.description }
            if (note.isNotBlank()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = content.copy(alpha = 0.8f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp, end = 8.dp)
                )
            }

            // ✓ and ✕ (from the day of the appointment) and the note pencil. Each opens the
            // note box with the attendance already chosen, so recording a visit is two taps.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (canRecordAttendance) {
                    // Neutral buttons that stand out on a green or red card; the one
                    // matching what is recorded is filled in.
                    AttendanceButton(
                        selected = attendance == Attendance.ATTENDED,
                        accent = OrthoColors.attended,
                        icon = Icons.Default.Check,
                        description = stringResource(R.string.note_attended),
                        onClick = { onMark(Attendance.ATTENDED) }
                    )
                    AttendanceButton(
                        selected = attendance == Attendance.MISSED,
                        accent = MaterialTheme.colorScheme.error,
                        icon = Icons.Default.Close,
                        description = stringResource(R.string.note_missed),
                        onClick = { onMark(Attendance.MISSED) }
                    )
                }
                FilledTonalIconButton(
                    onClick = { onMark(attendance) },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(Icons.Default.EditNote, contentDescription = stringResource(R.string.note_remark))
                }
            }
        }
    }
}

@Composable
private fun AttendanceButton(
    selected: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    FilledTonalIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (selected) accent else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.surface else accent
        )
    ) { Icon(icon, contentDescription = description) }
}

@Composable
private fun AttendanceLabel(attendance: Attendance) {
    val (label, color) = when (attendance) {
        Attendance.ATTENDED -> stringResource(R.string.note_attended) to OrthoColors.attended
        Attendance.MISSED -> stringResource(R.string.note_missed) to MaterialTheme.colorScheme.error
        Attendance.UNKNOWN -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, end = 8.dp)) {
        Icon(
            imageVector = if (attendance == Attendance.ATTENDED) Icons.Default.Check else Icons.Default.Close,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun AttendanceNoteDialog(
    event: TimelineEventWithPatient,
    preset: Attendance,
    canRecordAttendance: Boolean,
    onDismiss: () -> Unit,
    onSave: (Attendance, String) -> Unit
) {
    var attendance by rememberSaveable(event.id, preset) { mutableStateOf(preset) }
    var remark by rememberSaveable(event.id) { mutableStateOf(event.remark) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${event.patientName} — ${event.title}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                if (canRecordAttendance) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = attendance == Attendance.ATTENDED,
                        onClick = { attendance = Attendance.ATTENDED },
                        leadingIcon = { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        label = { Text(stringResource(R.string.note_attended)) }
                    )
                    FilterChip(
                        selected = attendance == Attendance.MISSED,
                        onClick = { attendance = Attendance.MISSED },
                        leadingIcon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        label = { Text(stringResource(R.string.note_missed)) }
                    )
                }
                OutlinedTextField(
                    value = remark,
                    onValueChange = { remark = it },
                    label = { Text(stringResource(R.string.note_remark)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            // Saving a future appointment also clears any attendance set by mistake.
            TextButton(onClick = { onSave(if (canRecordAttendance) attendance else Attendance.UNKNOWN, remark.trim()) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** "Aujourd'hui" / "Demain" instead of a date for those two days. */
@Composable
private fun dayHeader(day: Long, format: SimpleDateFormat, locale: Locale): String {
    val today = startOfDay(System.currentTimeMillis())
    // Calendar arithmetic, not +24 h, so a daylight-saving change cannot break "Demain".
    val tomorrow = Calendar.getInstance().apply {
        timeInMillis = today
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
    return when (day) {
        today -> stringResource(R.string.widget_today_label)
        tomorrow -> stringResource(R.string.widget_tomorrow)
        else -> format.format(Date(day)).replaceFirstChar { it.titlecase(locale) }
    }
}
