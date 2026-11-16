package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.NoteEntity
import com.example.data.local.model.BoneDetail
import com.example.data.repository.OrthoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SectionFilter {
    ALL, ANATOMY, APPROACH, REDUCTION, FIXATION, PEARLS, COMPLICATIONS, REHAB
}

data class BoneDetailUiState(
    val detail: BoneDetail? = null,
    val selectedFilter: SectionFilter = SectionFilter.ALL,
    val isLoading: Boolean = true,
    val isNoteDialogOpen: Boolean = false,
    val noteToEdit: NoteEntity? = null
)

class BoneDetailViewModel(
    private val repository: OrthoRepository,
    private val boneId: String
) : ViewModel() {

    private val _selectedFilter = MutableStateFlow(SectionFilter.ALL)
    val selectedFilter = _selectedFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _isNoteDialogOpen = MutableStateFlow(false)
    val isNoteDialogOpen = _isNoteDialogOpen.asStateFlow()

    private val _noteToEdit = MutableStateFlow<NoteEntity?>(null)
    val noteToEdit = _noteToEdit.asStateFlow()

    val uiState: StateFlow<BoneDetailUiState> = repository.getBoneDetail(boneId)
        .flatMapLatest { detail ->
            flowOf(
                BoneDetailUiState(
                    detail = detail,
                    selectedFilter = _selectedFilter.value,
                    isLoading = false,
                    isNoteDialogOpen = _isNoteDialogOpen.value,
                    noteToEdit = _noteToEdit.value
                )
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = BoneDetailUiState(isLoading = true)
        )

    fun setSectionFilter(filter: SectionFilter) {
        _selectedFilter.value = filter
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleBookmark() {
        viewModelScope.launch {
            repository.toggleBookmark(boneId)
        }
    }

    fun openAddNoteDialog() {
        _noteToEdit.value = null
        _isNoteDialogOpen.value = true
    }

    fun openEditNoteDialog(note: NoteEntity) {
        _noteToEdit.value = note
        _isNoteDialogOpen.value = true
    }

    fun closeNoteDialog() {
        _isNoteDialogOpen.value = false
        _noteToEdit.value = null
    }

    fun saveNote(note: NoteEntity) {
        viewModelScope.launch {
            repository.saveNote(note)
            closeNoteDialog()
        }
    }

    fun deleteNote(noteId: String) {
        viewModelScope.launch {
            repository.deleteNote(noteId)
        }
    }

    companion object {
        fun provideFactory(
            repository: OrthoRepository,
            boneId: String
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return BoneDetailViewModel(repository, boneId) as T
            }
        }
    }
}
