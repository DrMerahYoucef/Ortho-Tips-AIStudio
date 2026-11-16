package com.example.viewmodel

import com.example.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.SurgeryEntity
import com.example.data.repository.PatientRepository
import com.example.data.repository.SuggestionField
import com.example.data.repository.SuggestionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SurgeryFormState(
    val surgeryDate: Long = System.currentTimeMillis(),
    val hospitalClinic: String = "",
    val surgeon: String = "",
    val assistants: String = "",
    val anesthesiaType: String = "",
    val diagnosis: String = "",
    val procedure: String = "",
    val materialsUsed: String = "",
    val complications: String = "",
    val postOpInstructions: String = "",
    val operativeProtocol: String = ""
)

data class SurgeryEditUiState(
    val form: SurgeryFormState = SurgeryFormState(),
    /** String resource, resolved by the screen, so the message follows the app language. */
    @androidx.annotation.StringRes val procedureError: Int? = null,
    val isSaving: Boolean = false,
    val savedSurgeryId: String? = null,
    /** The form as it was loaded or last saved; what "unsaved changes" compares against. */
    val savedForm: SurgeryFormState = SurgeryFormState()
) {
    val hasUnsavedChanges: Boolean get() = form != savedForm
}

/** Every surgery field wired to smart suggestions, in the order the form learns from on save. */
private val SUGGESTION_FIELDS = listOf(
    SuggestionField.SURGERY_HOSPITAL,
    SuggestionField.SURGERY_SURGEON,
    SuggestionField.SURGERY_ASSISTANTS,
    SuggestionField.SURGERY_ANESTHESIA,
    SuggestionField.SURGERY_DIAGNOSIS,
    SuggestionField.SURGERY_PROCEDURE,
    SuggestionField.SURGERY_MATERIALS,
    SuggestionField.SURGERY_COMPLICATIONS,
    SuggestionField.SURGERY_POSTOP,
    SuggestionField.SURGERY_PROTOCOL
)

class SurgeryEditViewModel(
    private val repository: PatientRepository,
    private val suggestionRepository: SuggestionRepository,
    private val patientId: String,
    private val existingSurgery: SurgeryEntity?
) : ViewModel() {

    private val loadedForm: SurgeryFormState = run {
        val it = existingSurgery
        if (it != null) {
            SurgeryFormState(
                surgeryDate = it.surgeryDate,
                hospitalClinic = it.hospitalClinic,
                surgeon = it.surgeon,
                assistants = it.assistants,
                anesthesiaType = it.anesthesiaType,
                diagnosis = it.diagnosis,
                procedure = it.procedure,
                materialsUsed = it.materialsUsed,
                complications = it.complications,
                postOpInstructions = it.postOpInstructions,
                operativeProtocol = it.operativeProtocol
            )
        } else SurgeryFormState()
    }

    private val _uiState = MutableStateFlow(
        SurgeryEditUiState(form = loadedForm, savedForm = loadedForm)
    )
    val uiState: StateFlow<SurgeryEditUiState> = _uiState.asStateFlow()

    val suggestionsEnabled: StateFlow<Boolean> = suggestionRepository.suggestionsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /** fieldKey -> ranked list of previously-typed values, for every smart field on this form. */
    val suggestions: StateFlow<Map<String, List<String>>> = combine(
        SUGGESTION_FIELDS.map { key -> suggestionRepository.suggestionsFor(key) }
    ) { lists ->
        SUGGESTION_FIELDS.zip(lists).toMap()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun update(transform: (SurgeryFormState) -> SurgeryFormState) {
        _uiState.value = _uiState.value.copy(form = transform(_uiState.value.form), procedureError = null)
    }

    fun setSuggestionsEnabled(enabled: Boolean) {
        viewModelScope.launch { suggestionRepository.setEnabled(enabled) }
    }

    /** Removes a learned value after the surgeon long-presses it. */
    fun forget(fieldKey: String, value: String) {
        viewModelScope.launch { suggestionRepository.forget(fieldKey, value) }
    }

    fun save() {
        val form = _uiState.value.form
        if (form.procedure.isBlank()) {
            _uiState.value = _uiState.value.copy(procedureError = R.string.surgery_edit_error_procedure_required)
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val id = existingSurgery?.id ?: java.util.UUID.randomUUID().toString()
            repository.saveSurgery(
                SurgeryEntity(
                    id = id,
                    patientId = patientId,
                    surgeryDate = form.surgeryDate,
                    hospitalClinic = form.hospitalClinic,
                    surgeon = form.surgeon,
                    assistants = form.assistants,
                    anesthesiaType = form.anesthesiaType,
                    diagnosis = form.diagnosis,
                    procedure = form.procedure.trim(),
                    materialsUsed = form.materialsUsed,
                    complications = form.complications,
                    postOpInstructions = form.postOpInstructions,
                    operativeProtocol = form.operativeProtocol,
                    createdAt = existingSurgery?.createdAt ?: System.currentTimeMillis()
                )
            )
            learnFromForm(form)
            _uiState.value = _uiState.value.copy(isSaving = false, savedSurgeryId = id, savedForm = form)
        }
    }

    private suspend fun learnFromForm(form: SurgeryFormState) {
        // One call instead of one per field: learnRecord also records which
        // values appeared together, which is what lets the surgery form predict
        // the next field from the one before it.
        suggestionRepository.learnRecord(
            mapOf(
                SuggestionField.SURGERY_HOSPITAL to form.hospitalClinic,
                SuggestionField.SURGERY_SURGEON to form.surgeon,
                SuggestionField.SURGERY_ASSISTANTS to form.assistants,
                SuggestionField.SURGERY_ANESTHESIA to form.anesthesiaType,
                SuggestionField.SURGERY_DIAGNOSIS to form.diagnosis,
                SuggestionField.SURGERY_PROCEDURE to form.procedure,
                SuggestionField.SURGERY_MATERIALS to form.materialsUsed,
                SuggestionField.SURGERY_COMPLICATIONS to form.complications,
                SuggestionField.SURGERY_POSTOP to form.postOpInstructions,
                SuggestionField.SURGERY_PROTOCOL to form.operativeProtocol
            )
        )
    }

    companion object {
        fun provideFactory(
            repository: PatientRepository,
            suggestionRepository: SuggestionRepository,
            patientId: String,
            existingSurgery: SurgeryEntity?
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SurgeryEditViewModel(repository, suggestionRepository, patientId, existingSurgery) as T
            }
        }
    }
}
