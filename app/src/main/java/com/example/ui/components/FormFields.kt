package com.example.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.utils.DateFormat
import java.util.Calendar
import java.util.TimeZone

/** Heading above a group of form fields; announced as a heading by TalkBack. */
@Composable
fun FormSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(top = 12.dp)
            .semantics { heading() }
    )
}

/**
 * Standard text field for the forms. Single-line fields move to the next field
 * from the keyboard; errors use Material's supporting text so they are read out
 * with the field.
 */
@Composable
fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    isError: Boolean = false,
    errorText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        isError = isError,
        supportingText = if (isError && errorText != null) {
            { Text(errorText) }
        } else {
            null
        },
        keyboardOptions = if (singleLine) keyboardOptions.copy(imeAction = ImeAction.Next) else keyboardOptions,
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * Read-only field that opens a picker when tapped anywhere on it, not only on
 * the small trailing icon.
 */
@Composable
fun PickerField(
    value: String,
    label: String,
    icon: ImageVector,
    iconDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val currentOnClick by rememberUpdatedState(onClick)
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { if (it is PressInteraction.Release) currentOnClick() }
    }
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        trailingIcon = {
            IconButton(onClick = onClick) { Icon(icon, contentDescription = iconDescription) }
        },
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * Date of birth / injury date: year, then month, then day, so an old date is
 * three taps instead of scrolling a calendar back through decades.
 */
@Composable
fun DateField(
    label: String,
    dateMillis: Long?,
    onDateSelected: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    PickerField(
        value = DateFormat.formatDate(dateMillis).let { if (it == "—") "" else it },
        label = label,
        icon = Icons.Default.CalendarToday,
        iconDescription = stringResource(R.string.patient_edit_pick_date),
        onClick = { showPicker = true },
        modifier = modifier
    )
    if (showPicker) {
        StepwiseDatePickerDialog(
            initialMillis = dateMillis,
            onDismiss = { showPicker = false },
            onDateSelected = { picked -> onDateSelected(picked); showPicker = false }
        )
    }
}

/**
 * Calendar date picker for a moment that also has a time of day. Material's
 * picker works in UTC midnights; this converts to and from the local day and
 * keeps the time already set, so picking a date no longer resets an
 * appointment to 01:00.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarDateField(
    label: String,
    millis: Long,
    onChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    PickerField(
        value = DateFormat.formatDate(millis),
        label = label,
        icon = Icons.Default.CalendarToday,
        iconDescription = stringResource(R.string.patient_edit_pick_date),
        onClick = { showPicker = true },
        modifier = modifier
    )
    if (showPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = PickerTime.toPickerUtc(millis))
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(PickerTime.withDate(millis, it)) }
                    showPicker = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        ) {
            DatePicker(state = state)
        }
    }
}

/** Time of day for an appointment, in the phone's 12/24-hour setting. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(
    label: String,
    millis: Long,
    onChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val calendar = remember(millis) { Calendar.getInstance().apply { timeInMillis = millis } }
    val text = remember(millis) {
        android.text.format.DateFormat.getTimeFormat(context).format(calendar.time)
    }
    PickerField(
        value = text,
        label = label,
        icon = Icons.Outlined.Schedule,
        iconDescription = label,
        onClick = { showPicker = true },
        modifier = modifier
    )
    if (showPicker) {
        val state = rememberTimePickerState(
            initialHour = calendar.get(Calendar.HOUR_OF_DAY),
            initialMinute = calendar.get(Calendar.MINUTE),
            is24Hour = android.text.format.DateFormat.is24HourFormat(context)
        )
        AlertDialog(
            onDismissRequest = { showPicker = false },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onChange(PickerTime.withTime(millis, state.hour, state.minute))
                    showPicker = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

/** Conversions between local timestamps and Material's UTC-midnight dates. */
object PickerTime {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /** The local calendar day of [localMillis], as the UTC midnight the picker expects. */
    fun toPickerUtc(localMillis: Long): Long {
        val local = Calendar.getInstance().apply { timeInMillis = localMillis }
        return Calendar.getInstance(utc).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    /** [current] moved to the day picked in [pickerUtc], keeping its local time of day. */
    fun withDate(current: Long, pickerUtc: Long): Long {
        val picked = Calendar.getInstance(utc).apply { timeInMillis = pickerUtc }
        return Calendar.getInstance().apply {
            timeInMillis = current
            set(picked.get(Calendar.YEAR), picked.get(Calendar.MONTH), picked.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    /** [current] with its local time of day set to [hour]:[minute]. */
    fun withTime(current: Long, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = current
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
