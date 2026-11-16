package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.NoteEntity
import com.example.data.local.model.BoneSummary
import com.example.data.repository.OrthoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SavedTab {
    BOOKMARKS, NOTES
}

data class SavedUiState(
    val currentTab: SavedTab = SavedTab.BOOKMARKS,
    val bookmarks: List<BoneSummary> = emptyList(),
    val notes: List<NoteEntity> = emptyList(),
    val isAddNoteDialogOpen: Boolean = false,
    val noteToEdit: NoteEntity? = null
)

class SavedViewModel(
    private val repository: OrthoRepository
) : ViewModel() {

    private val _currentTab = MutableStateFlow(SavedTab.BOOKMARKS)
    val currentTab = _currentTab.asStateFlow()

    private val _isAddNoteDialogOpen = MutableStateFlow(false)
    val isAddNoteDialogOpen = _isAddNoteDialogOpen.asStateFlow()

    private val _noteToEdit = MutableStateFlow<NoteEntity?>(null)
    val noteToEdit = _noteToEdit.asStateFlow()

    val uiState: StateFlow<SavedUiState> = combine(
        _currentTab,
        repository.getBookmarkedBones(),
        repository.getAllNotes(),
        _isAddNoteDialogOpen,
        _noteToEdit
    ) { tab, bookmarks, notes, isDialogOpen, noteToEdit ->
        SavedUiState(
            currentTab = tab,
            bookmarks = bookmarks,
            notes = notes,
            isAddNoteDialogOpen = isDialogOpen,
            noteToEdit = noteToEdit
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SavedUiState()
    )

    fun selectTab(tab: SavedTab) {
        _currentTab.value = tab
    }

    fun toggleBookmark(boneId: String) {
        viewModelScope.launch {
            repository.toggleBookmark(boneId)
        }
    }

    fun openAddNoteDialog() {
        _noteToEdit.value = null
        _isAddNoteDialogOpen.value = true
    }

    fun openEditNoteDialog(note: NoteEntity) {
        _noteToEdit.value = note
        _isAddNoteDialogOpen.value = true
    }

    fun closeNoteDialog() {
        _isAddNoteDialogOpen.value = false
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
        fun provideFactory(repository: OrthoRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SavedViewModel(repository) as T
                }
            }
    }
}
