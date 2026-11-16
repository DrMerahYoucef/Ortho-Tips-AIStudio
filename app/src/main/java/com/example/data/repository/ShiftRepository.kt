package com.example.data.repository

import com.example.data.local.OrthoDatabase
import com.example.data.local.entity.ShiftEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** On-call days. Stored as plain yyyy-MM-dd strings so they sort and compare directly. */
class ShiftRepository(
    database: OrthoDatabase,
    private val appContext: android.content.Context? = null
) {

    private val dao = database.shiftDao()

    fun all(): Flow<List<ShiftEntity>> = dao.getAll()

    fun forMonth(year: Int, monthZeroBased: Int): Flow<List<ShiftEntity>> {
        val start = Calendar.getInstance().apply {
            set(year, monthZeroBased, 1, 0, 0, 0)
        }
        val end = (start.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
        }
        return dao.getBetween(format(start.timeInMillis), format(end.timeInMillis))
    }

    suspend fun toggle(date: String, on: Boolean) = withContext(Dispatchers.IO) {
        if (on) dao.insert(ShiftEntity(date = date)) else dao.delete(date)
        appContext?.let { com.example.widget.OrthoWidgetProvider.refreshAll(it) }
    }

    suspend fun upcoming(limit: Int = 20): List<ShiftEntity> = withContext(Dispatchers.IO) {
        dao.getUpcoming(format(System.currentTimeMillis()), limit)
    }

    companion object {
        private val KEY = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        fun format(millis: Long): String = KEY.format(java.util.Date(millis))

        fun parse(date: String): Long = runCatching { KEY.parse(date)?.time ?: 0L }.getOrDefault(0L)

        fun today(): String = format(System.currentTimeMillis())
    }
}
