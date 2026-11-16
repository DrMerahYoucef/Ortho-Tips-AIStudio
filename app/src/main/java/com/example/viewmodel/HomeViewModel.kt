package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.model.BoneSummary
import com.example.data.local.model.ChapterWithCount
import com.example.data.repository.OrthoRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val chapters: List<ChapterWithCount> = emptyList(),
    val highYieldBones: List<BoneSummary> = emptyList(),
    val totalProceduresCount: Int = 0,
    val bookmarkedCount: Int = 0,
    val noteCount: Int = 0,
    val isLoading: Boolean = false
) {
    /** Installed but not yet filled: the guide's tab offers to import its data. */
    val isDatabaseEmpty: Boolean get() = !isLoading && chapters.isEmpty()
}

/** The operative guide's content, shown when its extension is opened from the grid. */
class HomeViewModel(
    private val repository: OrthoRepository
) : ViewModel() {

    private data class GuideContent(
        val chapters: List<ChapterWithCount>,
        val highYield: List<BoneSummary>,
        val bookmarkedCount: Int,
        val noteCount: Int
    )

    private val guideContent = combine(
        repository.getAllChaptersWithCounts(),
        repository.getHighYieldBones(),
        repository.getBookmarkedBones(),
        repository.getAllNotes()
    ) { chapters, highYield, bookmarks, notes ->
        GuideContent(chapters, highYield, bookmarks.size, notes.size)
    }

    val uiState: StateFlow<HomeUiState> = guideContent.map { content ->
        HomeUiState(
            chapters = content.chapters,
            highYieldBones = content.highYield,
            totalProceduresCount = content.chapters.sumOf { it.boneCount },
            bookmarkedCount = content.bookmarkedCount,
            noteCount = content.noteCount,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState(isLoading = true)
    )

    fun toggleBookmark(boneId: String) {
        viewModelScope.launch {
            repository.toggleBookmark(boneId)
        }
    }

    companion object {
        fun provideFactory(repository: OrthoRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HomeViewModel(repository) as T
                }
            }
    }
}
