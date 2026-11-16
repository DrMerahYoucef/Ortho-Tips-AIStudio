package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.model.SearchResultItem
import com.example.data.repository.OrthoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

enum class SearchCategoryFilter {
    ALL, UPPER_LIMB, LOWER_LIMB, SPINE, PEDIATRIC, ARTHROPLASTY, SPORTS, PEARLS_ONLY
}

data class SearchUiState(
    val query: String = "",
    val categoryFilter: SearchCategoryFilter = SearchCategoryFilter.ALL,
    val results: List<SearchResultItem> = emptyList(),
    val isSearching: Boolean = false
)

class SearchViewModel(
    private val repository: OrthoRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    private val _categoryFilter = MutableStateFlow(SearchCategoryFilter.ALL)
    val categoryFilter = _categoryFilter.asStateFlow()

    val uiState: StateFlow<SearchUiState> = combine(
        _query.debounce(150),
        _categoryFilter
    ) { q, filter ->
        Pair(q, filter)
    }.flatMapLatest { (q, filter) ->
        repository.searchAll(q).combine(_categoryFilter) { results, currentFilter ->
            val filtered = when (currentFilter) {
                SearchCategoryFilter.ALL -> results
                SearchCategoryFilter.PEARLS_ONLY -> results.filter { it.isPearl || it.sectionType == "PEARLS" }
                SearchCategoryFilter.UPPER_LIMB -> results.filter {
                    it.region.contains("Wrist", true) || it.region.contains("Arm", true) ||
                            it.region.contains("Shoulder", true) || it.region.contains("Forearm", true) ||
                            it.region.contains("Elbow", true)
                }
                SearchCategoryFilter.LOWER_LIMB -> results.filter {
                    it.region.contains("Hip", true) || it.region.contains("Thigh", true) ||
                            it.region.contains("Knee", true) || it.region.contains("Leg", true) ||
                            it.region.contains("Ankle", true) || it.region.contains("Foot", true)
                }
                SearchCategoryFilter.SPINE -> results.filter { it.region.contains("Spine", true) }
                SearchCategoryFilter.PEDIATRIC -> results.filter { it.region.contains("Pediatric", true) }
                SearchCategoryFilter.ARTHROPLASTY -> results.filter { it.region.contains("Joint", true) }
                SearchCategoryFilter.SPORTS -> results.filter { it.region.contains("Ligament", true) || it.region.contains("Sports", true) }
            }
            SearchUiState(
                query = q,
                categoryFilter = currentFilter,
                results = filtered,
                isSearching = false
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SearchUiState(isSearching = true)
    )

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    fun setCategoryFilter(filter: SearchCategoryFilter) {
        _categoryFilter.value = filter
    }

    fun clearQuery() {
        _query.value = ""
    }

    companion object {
        fun provideFactory(repository: OrthoRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SearchViewModel(repository) as T
                }
            }
    }
}
