package com.example.ui.screens.patients

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.example.ui.components.DateField
import com.example.ui.components.FormSectionLabel
import com.example.ui.components.FormTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import com.example.data.local.entity.BodySide
import com.example.data.repository.SuggestionField
import com.example.data.repository.ClassificationRepository
import com.example.ui.components.ClassificationPickerButton
import com.example.ui.components.SmartSuggestionField
import com.example.ui.components.SuggestionsToggleCard
import com.example.utils.DateFormat
import com.example.viewmodel.PatientEditViewModel
import com.example.viewmodel.PatientFormState
import com.example.R
import com.example.ui.components.rememberLeaveGuard
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientEditScreen(
    viewModel: PatientEditViewModel,
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

    LaunchedEffect(uiState.savedPatientId) {
        uiState.savedPatientId?.let { onSaved(it) }
    }

    // A failed save brings the first field in error into view instead of
    // leaving the message off-screen above.
    val listState = rememberLazyListState()
    LaunchedEffect(uiState.patientCodeError, uiState.nameError) {
        if (uiState.patientCodeError != null || uiState.nameError != null) listState.animateScrollToItem(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (uiState.isEditMode) R.string.patient_edit_title_edit else R.string.patient_edit_title_new), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = attemptBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
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
            item { FormSectionLabel(stringResource(R.string.patient_edit_section_identification)) }
            item {
                FormTextField(
                    value = uiState.form.patientCode,
                    onValueChange = { v -> viewModel.update { it.copy(patientCode = v) } },
                    label = stringResource(R.string.patient_edit_patient_id),
                    isError = uiState.patientCodeError != null,
                    errorText = uiState.patientCodeError?.let { stringResource(it) }
                )
            }
            item {
                FormTextField(
                    value = uiState.form.fullName,
                    onValueChange = { v -> viewModel.update { it.copy(fullName = v) } },
                    label = stringResource(R.string.patient_edit_full_name),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    isError = uiState.nameError != null,
                    errorText = uiState.nameError?.let { stringResource(it) }
                )
            }
            item {
                DateField(
                    label = stringResource(R.string.patient_edit_date_of_birth),
                    dateMillis = uiState.form.dateOfBirth,
                    onDateSelected = { v -> viewModel.update { it.copy(dateOfBirth = v) } }
                )
            }
            item {
                GenderSelector(
                    value = uiState.form.gender,
                    onValueChange = { v -> viewModel.update { it.copy(gender = v) } }
                )
            }

            item { FormSectionLabel(stringResource(R.string.patient_edit_section_contact)) }
            item { FormTextField(uiState.form.phone, { v -> viewModel.update { it.copy(phone = v) } }, stringResource(R.string.patient_edit_phone), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)) }
            item { FormTextField(uiState.form.email, { v -> viewModel.update { it.copy(email = v) } }, stringResource(R.string.patient_edit_email), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)) }
            item { FormTextField(uiState.form.address, { v -> viewModel.update { it.copy(address = v) } }, stringResource(R.string.patient_edit_address), singleLine = false, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)) }
            item { FormTextField(uiState.form.emergencyContactName, { v -> viewModel.update { it.copy(emergencyContactName = v) } }, stringResource(R.string.patient_edit_emergency_contact_name), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)) }
            item { FormTextField(uiState.form.emergencyContactPhone, { v -> viewModel.update { it.copy(emergencyContactPhone = v) } }, stringResource(R.string.patient_edit_emergency_contact_phone), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)) }

            item { FormSectionLabel(stringResource(R.string.patient_edit_section_insurance)) }
            item { FormTextField(uiState.form.insuranceProvider, { v -> viewModel.update { it.copy(insuranceProvider = v) } }, stringResource(R.string.patient_edit_insurance_provider), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)) }
            item { FormTextField(uiState.form.insuranceNumber, { v -> viewModel.update { it.copy(insuranceNumber = v) } }, stringResource(R.string.patient_edit_policy_number)) }

            item {
                SuggestionsToggleCard(
                    enabled = suggestionsEnabled,
                    onToggle = { viewModel.setSuggestionsEnabled(it) },
                    subtitle = stringResource(R.string.patient_edit_learns_from_what_you_type)
                )
            }

            item { FormSectionLabel(stringResource(R.string.patient_edit_section_medical_history)) }
            item {
                SmartSuggestionField(
                    value = uiState.form.medicalHistory,
                    onValueChange = { v -> viewModel.update { it.copy(medicalHistory = v) } },
                    label = stringResource(R.string.patient_edit_medical_history),
                    suggestions = suggestions[SuggestionField.MEDICAL_HISTORY].orEmpty(),
                    suggestionsEnabled = suggestionsEnabled,
                    onForget = { viewModel.forget(SuggestionField.MEDICAL_HISTORY, it) },
                    singleLine = false
                )
            }
            item {
                SmartSuggestionField(
                    value = uiState.form.allergies,
                    onValueChange = { v -> viewModel.update { it.copy(allergies = v) } },
                    label = stringResource(R.string.patient_edit_allergies),
                    suggestions = suggestions[SuggestionField.ALLERGIES].orEmpty(),
                    suggestionsEnabled = suggestionsEnabled,
                    onForget = { viewModel.forget(SuggestionField.ALLERGIES, it) },
                    singleLine = false
                )
            }
            item {
                SmartSuggestionField(
                    value = uiState.form.chronicDiseases,
                    onValueChange = { v -> viewModel.update { it.copy(chronicDiseases = v) } },
                    label = stringResource(R.string.patient_edit_chronic_diseases),
                    suggestions = suggestions[SuggestionField.CHRONIC_DISEASES].orEmpty(),
                    suggestionsEnabled = suggestionsEnabled,
                    onForget = { viewModel.forget(SuggestionField.CHRONIC_DISEASES, it) },
                    singleLine = false
                )
            }
            item {
                SmartSuggestionField(
                    value = uiState.form.previousSurgeries,
                    onValueChange = { v -> viewModel.update { it.copy(previousSurgeries = v) } },
                    label = stringResource(R.string.patient_edit_previous_surgeries),
                    suggestions = suggestions[SuggestionField.PREVIOUS_SURGERIES].orEmpty(),
                    suggestionsEnabled = suggestionsEnabled,
                    onForget = { viewModel.forget(SuggestionField.PREVIOUS_SURGERIES, it) },
                    singleLine = false
                )
            }
            item {
                SmartSuggestionField(
                    value = uiState.form.medications,
                    onValueChange = { v -> viewModel.update { it.copy(medications = v) } },
                    label = stringResource(R.string.patient_edit_medications),
                    suggestions = suggestions[SuggestionField.MEDICATIONS].orEmpty(),
                    suggestionsEnabled = suggestionsEnabled,
                    onForget = { viewModel.forget(SuggestionField.MEDICATIONS, it) },
                    singleLine = false
                )
            }
            item { FormTextField(uiState.form.generalNotes, { v -> viewModel.update { it.copy(generalNotes = v) } }, stringResource(R.string.patient_edit_notes), singleLine = false, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)) }

            item { FormSectionLabel(stringResource(R.string.patient_edit_section_orthopedic)) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmartSuggestionField(
                        value = uiState.form.diagnosis,
                        onValueChange = { v -> viewModel.update { it.copy(diagnosis = v) } },
                        label = stringResource(R.string.patient_edit_diagnosis),
                        suggestions = suggestions[SuggestionField.DIAGNOSIS].orEmpty(),
                        suggestionsEnabled = suggestionsEnabled,
                        onForget = { viewModel.forget(SuggestionField.DIAGNOSIS, it) },
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
                SmartSuggestionField(
                    value = uiState.form.bodyRegion,
                    onValueChange = { v -> viewModel.update { it.copy(bodyRegion = v) } },
                    label = stringResource(R.string.patient_edit_body_region),
                    suggestions = suggestions[SuggestionField.BODY_REGION].orEmpty(),
                    suggestionsEnabled = suggestionsEnabled
                )
            }
            item {
                SideDropdown(
                    value = uiState.form.side,
                    onValueChange = { v -> viewModel.update { it.copy(side = v) } }
                )
            }
            item {
                DateField(
                    label = stringResource(R.string.patient_edit_injury_date),
                    dateMillis = uiState.form.injuryDate,
                    onDateSelected = { v -> viewModel.update { it.copy(injuryDate = v) } }
                )
            }
            item {
                SmartSuggestionField(
                    value = uiState.form.traumaMechanism,
                    onValueChange = { v -> viewModel.update { it.copy(traumaMechanism = v) } },
                    label = stringResource(R.string.patient_edit_trauma_mechanism),
                    suggestions = suggestions[SuggestionField.TRAUMA_MECHANISM].orEmpty(),
                    suggestionsEnabled = suggestionsEnabled,
                    onForget = { viewModel.forget(SuggestionField.TRAUMA_MECHANISM, it) },
                    singleLine = false
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmartSuggestionField(
                        value = uiState.form.fractureClassification,
                        onValueChange = { v -> viewModel.update { it.copy(fractureClassification = v) } },
                        label = stringResource(R.string.patient_edit_fracture_classification),
                        suggestions = suggestions[SuggestionField.FRACTURE_CLASSIFICATION].orEmpty(),
                        suggestionsEnabled = suggestionsEnabled
                    )
                    ClassificationPickerButton(
                        repository = classificationRepository,
                        onClassificationSelected = { phrase ->
                            viewModel.update {
                                it.copy(fractureClassification = appendClassification(it.fractureClassification, phrase))
                            }
                        }
                    )
                }
            }
            item {
                SmartSuggestionField(
                    value = uiState.form.imagingFindings,
                    onValueChange = { v -> viewModel.update { it.copy(imagingFindings = v) } },
                    label = stringResource(R.string.patient_edit_imaging_findings),
                    suggestions = suggestions[SuggestionField.IMAGING_FINDINGS].orEmpty(),
                    suggestionsEnabled = suggestionsEnabled,
                    onForget = { viewModel.forget(SuggestionField.IMAGING_FINDINGS, it) },
                    singleLine = false
                )
            }
            item {
                SmartSuggestionField(
                    value = uiState.form.treatmentPlan,
                    onValueChange = { v -> viewModel.update { it.copy(treatmentPlan = v) } },
                    label = stringResource(R.string.patient_edit_treatment_plan),
                    suggestions = suggestions[SuggestionField.TREATMENT_PLAN].orEmpty(),
                    suggestionsEnabled = suggestionsEnabled,
                    onForget = { viewModel.forget(SuggestionField.TREATMENT_PLAN, it) },
                    singleLine = false
                )
            }

            item {
                Button(
                    onClick = { viewModel.save() },
                    enabled = !uiState.isSaving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(if (uiState.isSaving) R.string.patient_edit_saving else R.string.patient_edit_save_patient))
                }
            }
        }
    }
}

private fun appendClassification(existing: String, classification: String): String =
    if (existing.isBlank()) classification else "${existing.trimEnd()}\n$classification"

/**
 * Three choices shown at once: one tap instead of open-a-menu-then-pick. The
 * stored value is unchanged (the chosen word, as before); an older free-text
 * value simply shows no selection until a choice is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenderSelector(value: String, onValueChange: (String) -> Unit) {
    val options = listOf(
        stringResource(R.string.patient_edit_gender_male),
        stringResource(R.string.patient_edit_gender_female),
        stringResource(R.string.patient_edit_gender_other)
    )
    // Older records stored the English (or the other language's) word.
    val selectedIndex = when (value.trim().lowercase()) {
        "male", "homme" -> 0
        "female", "femme" -> 1
        "other", "autre" -> 2
        else -> options.indexOfFirst { it.equals(value.trim(), ignoreCase = true) }
    }
    Column {
        Text(
            text = stringResource(R.string.patient_edit_gender),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = selectedIndex == index,
                    onClick = { onValueChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size)
                ) { Text(option, maxLines = 1) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SideDropdown(value: BodySide, onValueChange: (BodySide) -> Unit) {
    val labels = mapOf(
        BodySide.LEFT to stringResource(R.string.patient_edit_side_left),
        BodySide.RIGHT to stringResource(R.string.patient_edit_side_right),
        BodySide.BILATERAL to stringResource(R.string.patient_edit_side_bilateral),
        BodySide.NOT_APPLICABLE to stringResource(R.string.patient_edit_side_not_applicable)
    )
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = labels[value] ?: value.name,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.patient_edit_side)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            BodySide.entries.forEach { side ->
                DropdownMenuItem(
                    text = { Text(labels[side] ?: side.name) },
                    onClick = { onValueChange(side); expanded = false }
                )
            }
        }
    }
}
