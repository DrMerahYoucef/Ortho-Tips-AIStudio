package com.example.ui.screens.patients

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import com.example.ui.components.CalendarDateField
import com.example.ui.components.TimeField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.utils.DateFormat
import com.example.viewmodel.TimelineEventEditViewModel
import com.example.R
import com.example.ui.components.rememberLeaveGuard
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineEventEditScreen(
    viewModel: TimelineEventEditViewModel,
    onNavigateBack: () -> Unit,
    onSaved: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val titleSuggestions by viewModel.titleSuggestions.collectAsStateWithLifecycle()
    val descriptionSuggestions by viewModel.descriptionSuggestions.collectAsStateWithLifecycle()
    val suggestionsEnabled by viewModel.suggestionsEnabled.collectAsStateWithLifecycle()

    // Leaving with edits still in the form asks first, for the arrow and for the
    // phone's back gesture.
    val attemptBack = rememberLeaveGuard(
        isDirty = uiState.hasUnsavedChanges,
        onSave = viewModel::save,
        onLeave = onNavigateBack
    )

    LaunchedEffect(uiState.savedEventId) {
        uiState.savedEventId?.let { onSaved(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.timeline_event_edit_timeline_event), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = attemptBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
                actions = {
                    TextButton(onClick = { viewModel.save() }, enabled = !uiState.isSaving) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.patient_edit_save))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        modifier = modifier
    ) { paddingValues ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            // The keyboard pushes the form up instead of covering the lower fields.
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .imePadding()
        ) {
            item {
                // Date and time side by side: the Agenda and the widget show the
                // appointment's time, which the form could not set before.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CalendarDateField(
                        label = stringResource(R.string.timeline_event_edit_date),
                        millis = uiState.form.eventDate,
                        onChange = { millis -> viewModel.update { it.copy(eventDate = millis) } },
                        modifier = Modifier.weight(1.4f)
                    )
                    TimeField(
                        label = stringResource(R.string.timeline_event_edit_time),
                        millis = uiState.form.eventDate,
                        onChange = { millis -> viewModel.update { it.copy(eventDate = millis) } },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                com.example.ui.components.SmartSuggestionField(
                    value = uiState.form.title,
                    onValueChange = { v -> viewModel.update { it.copy(title = v) } },
                    label = stringResource(R.string.timeline_event_edit_title),
                    suggestions = titleSuggestions,
                    suggestionsEnabled = suggestionsEnabled,
                    onForget = { viewModel.forget(com.example.viewmodel.TimelineSuggestionField.TITLE, it) },
                    isError = uiState.titleError != null,
                    errorText = uiState.titleError?.let { stringResource(it) }
                )
            }
            item {
                com.example.ui.components.SmartSuggestionField(
                    value = uiState.form.description,
                    onValueChange = { v -> viewModel.update { it.copy(description = v) } },
                    label = stringResource(R.string.timeline_event_edit_description),
                    suggestions = descriptionSuggestions,
                    suggestionsEnabled = suggestionsEnabled,
                    onForget = { viewModel.forget(com.example.viewmodel.TimelineSuggestionField.DESCRIPTION, it) },
                    singleLine = false
                )
            }
            item {
                Button(
                    onClick = { viewModel.save() },
                    enabled = !uiState.isSaving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(if (uiState.isSaving) R.string.patient_edit_saving else R.string.timeline_event_save))
                }
            }
        }
    }

}
