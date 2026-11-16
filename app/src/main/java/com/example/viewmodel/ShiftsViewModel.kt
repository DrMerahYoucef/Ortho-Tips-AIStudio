package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.ShiftEntity
import com.example.data.repository.ShiftRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.util.Calendar

data class ShiftMonth(val year: Int, val month: Int)

@OptIn(ExperimentalCoroutinesApi::class)
class ShiftsViewModel(private val repository: ShiftRepository) : ViewModel() {

    private val today = Calendar.getInstance()

    private val _month = MutableStateFlow(
        ShiftMonth(today.get(Calendar.YEAR), today.get(Calendar.MONTH))
    )
    val month: StateFlow<ShiftMonth> = _month.asStateFlow()

    private val _shifts = MutableStateFlow<Set<String>>(emptySet())

    /** Shifts of the month on screen, as yyyy-MM-dd keys. */
    val shiftsThisMonth: StateFlow<Set<String>> = _shifts.asStateFlow()

    init {
        viewModelScope.launch {
            _month
                .flatMapLatest { repository.forMonth(it.year, it.month) }
                .collect { list -> _shifts.value = list.map(ShiftEntity::date).toSet() }
        }
    }

    fun showMonth(year: Int, month: Int) {
        _month.value = ShiftMonth(year, month)
    }

    fun step(months: Int) {
        val calendar = Calendar.getInstance().apply {
            set(_month.value.year, _month.value.month, 1)
            add(Calendar.MONTH, months)
        }
        _month.value = ShiftMonth(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH))
    }

    fun toggle(date: String, on: Boolean) {
        viewModelScope.launch { repository.toggle(date, on) }
    }

    companion object {
        fun provideFactory(repository: ShiftRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ShiftsViewModel(repository) as T
            }
    }
}
