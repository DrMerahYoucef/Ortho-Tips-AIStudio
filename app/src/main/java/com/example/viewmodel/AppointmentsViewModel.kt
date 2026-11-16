package com.example.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.InvalidationTracker
import com.example.data.local.OrthoDatabase
import com.example.data.local.entity.Attendance
import com.example.data.local.model.TimelineEventWithPatient
import com.example.widget.OrthoWidgetProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import java.util.Calendar

/** Which slice of the diary is on screen. */
enum class AppointmentRange { UPCOMING, PAST }

/**
 * The Agenda diary. It reads with the widget's query and saves with the calls the
 * event form uses, so it does not depend on any newer DAO or repository method,
 * and it re-reads whenever Room reports a change to the
 * timeline or patients tables: a visit marked in the widget shows here at once,
 * and one marked here refreshes the widget.
 */
class AppointmentsViewModel(private val appContext: Context) : ViewModel() {

    private val database = OrthoDatabase.getInstance(appContext)
    private val dao = database.timelineEventDao()

    private val _range = MutableStateFlow(AppointmentRange.UPCOMING)
    val range: StateFlow<AppointmentRange> = _range.asStateFlow()

    /** Null until the first result arrives, so "no appointments" never flashes while loading. */
    private val _appointments = MutableStateFlow<List<TimelineEventWithPatient>?>(null)
    val appointments: StateFlow<List<TimelineEventWithPatient>?> = _appointments.asStateFlow()

    private var collector: kotlinx.coroutines.Job? = null

    init {
        observe(AppointmentRange.UPCOMING)
    }

    fun showRange(range: AppointmentRange) {
        if (_range.value == range) return
        _range.value = range
        observe(range)
    }

    /** Emits once now, then after every write to the timeline or patients tables. */
    private fun tableChanges(): Flow<Unit> = callbackFlow {
        val observer = object : InvalidationTracker.Observer(arrayOf("timeline_events", "patients")) {
            override fun onInvalidated(tables: Set<String>) {
                trySend(Unit)
            }
        }
        database.invalidationTracker.addObserver(observer)
        trySend(Unit)
        awaitClose { database.invalidationTracker.removeObserver(observer) }
    }

    private fun observe(range: AppointmentRange) {
        collector?.cancel()
        _appointments.value = null
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        // Upcoming counts from the start of today, so this morning's appointments
        // do not disappear at lunchtime.
        val (from, to) = when (range) {
            AppointmentRange.UPCOMING -> startOfToday to Long.MAX_VALUE
            AppointmentRange.PAST -> 0L to startOfToday - 1
        }
        collector = viewModelScope.launch {
            tableChanges().conflate().collect {
                val list = dao.getEventsWithPatientBetween(from, to)
                _appointments.value =
                    if (range == AppointmentRange.PAST) list.sortedByDescending { it.eventDate } else list
            }
        }
    }

    fun setAttendance(eventId: String, attendance: Attendance, remark: String) {
        viewModelScope.launch {
            // Read-modify-write with the two calls the event form itself relies on,
            // so this builds against every version of the DAO. Nothing references
            // timeline_events as a parent, so the REPLACE insert is a plain update.
            val event = dao.getEventDirect(eventId) ?: return@launch
            dao.insertEvent(
                event.copy(
                    attendance = attendance.name,
                    remark = remark,
                    updatedAt = System.currentTimeMillis()
                )
            )
            OrthoWidgetProvider.refreshAll(appContext)
        }
    }

    companion object {
        fun provideFactory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppointmentsViewModel(context.applicationContext) as T
            }
    }
}
