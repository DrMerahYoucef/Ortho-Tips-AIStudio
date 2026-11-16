package com.example.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import com.example.ui.components.PatientValueLabels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.model.PatientSummary
import com.example.data.repository.PatientRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class PatientListTab { ACTIVE, ARCHIVED }

data class PatientListUiState(
    val patients: List<PatientSummary> = emptyList(),
    val tab: PatientListTab = PatientListTab.ACTIVE,
    val searchQuery: String = "",
    val regionFilter: String = ALL_REGIONS,
    /** Body regions present on any patient, in the app language, for the filter chips. */
    val regions: List<String> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val activeCount: Int = 0,
    val archivedCount: Int = 0,
    val isLoading: Boolean = true
) {
    val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()

    // Computed once per state, not on every read during recomposition.
    val filteredPatients: List<PatientSummary> by lazy {
        if (regionFilter == ALL_REGIONS) patients
        else patients.filter { PatientValueLabels.bodyRegion(it.bodyRegion).equals(regionFilter, ignoreCase = true) }
    }

    val isEmpty: Boolean get() = !isLoading && filteredPatients.isEmpty()

    companion object {
        /** Internal "no filter" value; the chip shows a translated label. */
        const val ALL_REGIONS = "All"
    }
}

class PatientListViewModel(
    private val repository: PatientRepository
) : ViewModel() {

    private val _tab = MutableStateFlow(PatientListTab.ACTIVE)
    private val _searchQuery = MutableStateFlow("")
    private val _regionFilter = MutableStateFlow(PatientListUiState.ALL_REGIONS)
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())

    // One five-way combine instead of a chain of Pair/Triple/Tuple wrappers.
    private val inputs = combine(
        repository.getActivePatientSummaries(),
        repository.getArchivedPatientSummaries(),
        _tab,
        _searchQuery,
        _regionFilter
    ) { active, archived, tab, query, region -> ListInputs(active, archived, tab, query, region) }

    val uiState: StateFlow<PatientListUiState> = inputs
        .combine(_selectedIds) { (active, archived, tab, query, region), selected ->
            val baseList = if (tab == PatientListTab.ACTIVE) active else archived
            val searchedList = if (query.isNotBlank()) {
                baseList.filter { patient ->
                    patient.fullName.contains(query, ignoreCase = true) ||
                    patient.patientCode.contains(query, ignoreCase = true) ||
                    patient.diagnosis.contains(query, ignoreCase = true) ||
                    patient.bodyRegion.contains(query, ignoreCase = true)
                }
            } else {
                baseList
            }

            PatientListUiState(
                patients = searchedList,
                tab = tab,
                searchQuery = query,
                regionFilter = region,
                regions = (active + archived)
                    .map { PatientValueLabels.bodyRegion(it.bodyRegion).trim() }
                    .filter { it.isNotEmpty() }
                    .distinctBy { it.lowercase() }
                    .sortedBy { it.lowercase() },
                selectedIds = selected,
                activeCount = active.size,
                archivedCount = archived.size,
                isLoading = false
            )
        }
        // Searching and grouping a large list happens off the main thread.
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PatientListUiState(isLoading = true)
        )

    fun setTab(tab: PatientListTab) {
        _tab.value = tab
        _selectedIds.value = emptySet()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setRegionFilter(region: String) {
        _regionFilter.value = region
    }

    fun toggleSelection(patientId: String) {
        _selectedIds.value = _selectedIds.value.let { current ->
            if (current.contains(patientId)) current - patientId else current + patientId
        }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun archiveSelected() = setArchivedForSelected(true)

    fun unarchiveSelected() = setArchivedForSelected(false)

    private fun setArchivedForSelected(archived: Boolean) {
        val ids = _selectedIds.value
        viewModelScope.launch {
            ids.forEach { repository.setArchived(it, archived) }
            _selectedIds.value = emptySet()
        }
    }

    fun deleteSelected() {
        val ids = _selectedIds.value.toList()
        viewModelScope.launch {
            repository.deletePatients(ids)
            _selectedIds.value = emptySet()
        }
    }

    companion object {
        fun provideFactory(repository: PatientRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PatientListViewModel(repository) as T
                }
            }
    }
}

private data class ListInputs(
    val active: List<PatientSummary>,
    val archived: List<PatientSummary>,
    val tab: PatientListTab,
    val query: String,
    val region: String
)
