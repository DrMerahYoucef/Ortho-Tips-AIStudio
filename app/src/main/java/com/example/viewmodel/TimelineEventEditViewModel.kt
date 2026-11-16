package com.example.viewmodel

import com.example.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.TimelineEventEntity
import com.example.data.repository.PatientRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TimelineEventFormState(
    val eventDate: Long = System.currentTimeMillis(),
    val title: String = "",
    val description: String = ""
)

data class TimelineEventEditUiState(
    val form: TimelineEventFormState = TimelineEventFormState(),
    /** String resource, resolved by the screen, so the message follows the app language. */
    @androidx.annotation.StringRes val titleError: Int? = null,
    val isSaving: Boolean = false,
    val savedEventId: String? = null,
    /** The form as it was loaded or last saved; what "unsaved changes" compares against. */
    val savedForm: TimelineEventFormState = TimelineEventFormState()
) {
    val hasUnsavedChanges: Boolean get() = form != savedForm
}

/** Fields on this form that learn from what the surgeon types. */
object TimelineSuggestionField {
    const val TITLE = "timeline.title"
    const val DESCRIPTION = "timeline.description"
}

class TimelineEventEditViewModel(
    private val repository: PatientRepository,
    private val suggestionRepository: com.example.data.repository.SuggestionRepository,
    private val patientId: String,
    private val existingEvent: TimelineEventEntity?
) : ViewModel() {

    val suggestionsEnabled = suggestionRepository.suggestionsEnabled
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), true)

    val titleSuggestions = suggestionRepository.suggestionsFor(TimelineSuggestionField.TITLE)
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

    val descriptionSuggestions = suggestionRepository.suggestionsFor(TimelineSuggestionField.DESCRIPTION)
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(
        TimelineEventEditUiState(
            form = existingEvent?.let {
                TimelineEventFormState(eventDate = it.eventDate, title = it.title, description = it.description)
            } ?: TimelineEventFormState(),
            savedForm = existingEvent?.let {
                TimelineEventFormState(eventDate = it.eventDate, title = it.title, description = it.description)
            } ?: TimelineEventFormState()
        )
    )
    val uiState: StateFlow<TimelineEventEditUiState> = _uiState.asStateFlow()

    fun update(transform: (TimelineEventFormState) -> TimelineEventFormState) {
        _uiState.value = _uiState.value.copy(form = transform(_uiState.value.form), titleError = null)
    }

    /** Removes a learned value after the surgeon long-presses it. */
    fun forget(fieldKey: String, value: String) {
        viewModelScope.launch { suggestionRepository.forget(fieldKey, value) }
    }

    fun save() {
        val form = _uiState.value.form
        if (form.title.isBlank()) {
            _uiState.value = _uiState.value.copy(titleError = R.string.timeline_event_error_title_required)
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val id = existingEvent?.id ?: java.util.UUID.randomUUID().toString()
            repository.saveTimelineEvent(
                TimelineEventEntity(
                    id = id,
                    patientId = patientId,
                    eventDate = form.eventDate,
                    title = form.title.trim(),
                    description = form.description,
                    attendance = existingEvent?.attendance
                        ?: com.example.data.local.entity.Attendance.UNKNOWN.name,
                    remark = existingEvent?.remark.orEmpty(),
                    createdAt = existingEvent?.createdAt ?: System.currentTimeMillis()
                )
            )
            // The form learns the wording actually used, so the next event offers it.
            suggestionRepository.learnRecord(
                mapOf(
                    TimelineSuggestionField.TITLE to form.title,
                    TimelineSuggestionField.DESCRIPTION to form.description
                )
            )
            _uiState.value = _uiState.value.copy(isSaving = false, savedEventId = id, savedForm = form)
        }
    }

    companion object {
        fun provideFactory(
            repository: PatientRepository,
            suggestionRepository: com.example.data.repository.SuggestionRepository,
            patientId: String,
            existingEvent: TimelineEventEntity?
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return TimelineEventEditViewModel(
                    repository, suggestionRepository, patientId, existingEvent
                ) as T
            }
        }
    }
}
