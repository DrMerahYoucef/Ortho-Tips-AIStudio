package com.example.ui.screens.patients

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.rememberLazyListState
import com.example.ui.components.CalendarDateField
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
import com.example.data.repository.ClassificationRepository
import com.example.data.repository.SuggestionField
import com.example.ui.components.BulletProtocolField
import com.example.ui.components.ClassificationPickerButton
import com.example.ui.components.FrenchVoiceInputButton
import com.example.ui.components.SmartSuggestionField
import com.example.ui.components.SuggestionsToggleCard
import com.example.utils.DateFormat
import com.example.viewmodel.SurgeryEditViewModel
import com.example.R
import com.example.ui.components.rememberLeaveGuard
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurgeryEditScreen(
    viewModel: SurgeryEditViewModel,
    classificationRepository: ClassificationRepository,
    onNavigateBack: () -> Unit,
    onSaved: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val suggestionsEnabled by viewModel.suggestionsEnabled.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()

    // Leaving with edits still in the form asks first, for the arrow and for the
    // phone's back gesture.
    val attemptBack = rememberLeaveGuard(
        isDirty = uiState.hasUnsavedChanges,
        onSave = viewModel::save,
        onLeave = onNavigateBack
    )

    LaunchedEffect(uiState.savedSurgeryId) {
        uiState.savedSurgeryId?.let { onSaved(it) }
    }

    // A missing procedure brings its field into view.
    val listState = rememberLazyListState()
    LaunchedEffect(uiState.procedureError) {
        if (uiState.procedureError != null) listState.animateScrollToItem(2)
    }
    val procedureError = uiState.procedureError

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.surgery_edit_surgery_record), fontWeight = FontWeight.Bold) },
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
            state = listState,
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
                SuggestionsToggleCard(
                    enabled = suggestionsEnabled,
                    onToggle = { viewModel.setSuggestionsEnabled(it) },
                    subtitle = stringResource(R.string.surgery_edit_autofills_hospital_surgeon_assistants_anesthesi)
                )
            }
            item {
                CalendarDateField(
                    label = stringResource(R.string.surgery_edit_surgery_date),
                    millis = uiState.form.surgeryDate,
                    onChange = { millis -> viewModel.update { it.copy(surgeryDate = millis) } }
                )
            }
            item {
                Column {
                    OutlinedTextField(
                        value = uiState.form.procedure,
                        onValueChange = { v -> viewModel.update { it.copy(procedure = v) } },
                        label = { Text(stringResource(R.string.surgery_edit_procedure)) },
                        trailingIcon = {
                            FrenchVoiceInputButton { spokenText ->
                                val separator = if (uiState.form.procedure.isBlank()) "" else " "
                                viewModel.update { it.copy(procedure = uiState.form.procedure + separator + spokenText.trim()) }
                            }
                        },
                        isError = uiState.procedureError != null,
                        supportingText = if (procedureError != null) {
                            { Text(stringResource(procedureError)) }
                        } else {
                            null
                        },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            item {
                SmartSuggestionField(
                    value = uiState.form.hospitalClinic,
                    onValueChange = { v -> viewModel.update { it.copy(hospitalClinic = v) } },
                    label = stringResource(R.string.pdf_option_hospital),
                    suggestions = suggestions[SuggestionField.SURGERY_HOSPITAL].orEmpty(),
                    suggestionsEnabled = suggestionsEnabled
                )
            }
            item {
                SmartSuggestionField(
                    value = uiState.form.surgeon,
                    onValueChange = { v -> viewModel.update { it.copy(surgeon = v) } },
                    label = stringResource(R.string.pdf_option_surgeon),
                    suggestions = suggestions[SuggestionField.SURGERY_SURGEON].orEmpty(),
                    suggestionsEnabled = suggestionsEnabled
                )
            }
            item {
                SmartSuggestionField(
                    value = uiState.form.assistants,
                    onValueChange = { v -> viewModel.update { it.copy(assistants = v) } },
                    label = stringResource(R.string.pdf_option_assistants),
                    suggestions = suggestions[SuggestionField.SURGERY_ASSISTANTS].orEmpty(),
                    suggestionsEnabled = suggestionsEnabled
                )
            }
            item {
                SmartSuggestionField(
                    value = uiState.form.anesthesiaType,
                    onValueChange = { v -> viewModel.update { it.copy(anesthesiaType = v) } },
                    label = stringResource(R.string.pdf_option_anesthesia),
                    suggestions = suggestions[SuggestionField.SURGERY_ANESTHESIA].orEmpty(),
                    suggestionsEnabled = suggestionsEnabled
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmartSuggestionField(
                        value = uiState.form.diagnosis,
                        onValueChange = { v -> viewModel.update { it.copy(diagnosis = v) } },
                        label = stringResource(R.string.patient_edit_diagnosis),
                        suggestions = suggestions[SuggestionField.SURGERY_DIAGNOSIS].orEmpty(),
                        suggestionsEnabled = suggestionsEnabled,
                        onForget = { viewModel.forget(SuggestionField.SURGERY_DIAGNOSIS, it) },
                        singleLine = false
                    )
                    ClassificationPickerButton(
                        repository = classificationRepository,
                        onClassificationSelected = { phrase ->
                            viewModel.update {
                                it.copy(diagnosis = appendClassification(it.diagnosis, phrase))
                            }
                        }
                    )
                }
            }
            item {
                BulletProtocolField(
                    value = uiState.form.operativeProtocol,
                    onValueChange = { v -> viewModel.update { it.copy(operativeProtocol = v) } },
                    suggestions = suggestions[SuggestionField.SURGERY_PROTOCOL].orEmpty(),
                    suggestionsEnabled = suggestionsEnabled,
                    onForget = { viewModel.forget(SuggestionField.SURGERY_PROTOCOL, it) },
                    label = stringResource(R.string.pdf_option_operative_steps)
                )
            }
            item {
                SmartSuggestionField(
                    value = uiState.form.materialsUsed,
                    onValueChange = { v -> viewModel.update { it.copy(materialsUsed = v) } },
                    label = stringResource(R.string.pdf_option_materials),
                    suggestions = suggestions[SuggestionField.SURGERY_MATERIALS].orEmpty(),
                    suggestionsEnabled = suggestionsEnabled,
                    onForget = { viewModel.forget(SuggestionField.SURGERY_MATERIALS, it) },
                    singleLine = false
                )
            }
            item {
                SmartSuggestionField(
                    value = uiState.form.complications,
                    onValueChange = { v -> viewModel.update { it.copy(complications = v) } },
                    label = stringResource(R.string.pdf_option_complications),
                    suggestions = suggestions[SuggestionField.SURGERY_COMPLICATIONS].orEmpty(),
                    suggestionsEnabled = suggestionsEnabled,
                    onForget = { viewModel.forget(SuggestionField.SURGERY_COMPLICATIONS, it) },
                    singleLine = false
                )
            }
            item {
                SmartSuggestionField(
                    value = uiState.form.postOpInstructions,
                    onValueChange = { v -> viewModel.update { it.copy(postOpInstructions = v) } },
                    label = stringResource(R.string.pdf_option_postop),
                    suggestions = suggestions[SuggestionField.SURGERY_POSTOP].orEmpty(),
                    suggestionsEnabled = suggestionsEnabled,
                    onForget = { viewModel.forget(SuggestionField.SURGERY_POSTOP, it) },
                    singleLine = false
                )
            }
            item {
                Button(
                    onClick = { viewModel.save() },
                    enabled = !uiState.isSaving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (uiState.isSaving) "Saving..." else "Save surgery")
                }
            }
        }
    }

}

private fun appendClassification(existing: String, classification: String): String =
    if (existing.isBlank()) classification else "${existing.trimEnd()}\n$classification"
