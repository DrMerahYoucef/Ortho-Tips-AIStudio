package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.model.PatientDetail
import com.example.data.repository.PatientRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PatientDetailUiState(
    val detail: PatientDetail? = null,
    val isLoading: Boolean = true,
    // true once the patient has been deleted from under this screen, so the UI can navigate back
    val wasDeleted: Boolean = false
)

class PatientDetailViewModel(
    private val repository: PatientRepository,
    private val patientId: String
) : ViewModel() {

    val uiState: StateFlow<PatientDetailUiState> = repository.getPatientDetail(patientId)
        .map { detail -> PatientDetailUiState(detail = detail, isLoading = false, wasDeleted = detail == null) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PatientDetailUiState(isLoading = true)
        )

    fun archive() {
        viewModelScope.launch { repository.setArchived(patientId, true) }
    }

    fun unarchive() {
        viewModelScope.launch { repository.setArchived(patientId, false) }
    }

    fun delete() {
        viewModelScope.launch { repository.deletePatient(patientId) }
    }

    fun deleteTimelineEvent(eventId: String) {
        viewModelScope.launch { repository.deleteTimelineEvent(eventId) }
    }

    fun deleteSurgery(surgeryId: String) {
        viewModelScope.launch { repository.deleteSurgery(surgeryId) }
    }

    fun deleteImage(imageId: String) {
        viewModelScope.launch { repository.deleteImage(imageId) }
    }

    fun deleteImages(imageIds: Set<String>) {
        viewModelScope.launch { imageIds.forEach { repository.deleteImage(it) } }
    }

    fun updateImageCategory(imageIds: Set<String>, category: com.example.data.local.entity.ImageCategory) {
        viewModelScope.launch {
            imageIds.forEach { id ->
                val image = repository.getImageOrNull(id) ?: return@forEach
                repository.saveImage(image.copy(category = category.name))
            }
        }
    }

    fun deleteDocument(documentId: String) {
        viewModelScope.launch { repository.deleteDocument(documentId) }
    }

    companion object {
        fun provideFactory(
            repository: PatientRepository,
            patientId: String
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PatientDetailViewModel(repository, patientId) as T
            }
        }
    }
}
