package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.TimelineEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TimelineEventDao {

    @Query("SELECT * FROM timeline_events WHERE patientId = :patientId ORDER BY eventDate DESC")
    fun getEventsForPatient(patientId: String): Flow<List<TimelineEventEntity>>

    @Query("SELECT * FROM timeline_events WHERE id = :id LIMIT 1")
    fun getEventById(id: String): Flow<TimelineEventEntity?>

    @Query("SELECT * FROM timeline_events WHERE id = :id LIMIT 1")
    suspend fun getEventDirect(id: String): TimelineEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: TimelineEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<TimelineEventEntity>)

    @Update
    suspend fun updateEvent(event: TimelineEventEntity)

    @Query("DELETE FROM timeline_events WHERE id = :id")
    suspend fun deleteEvent(id: String)

    /** Events in a window, with the patient's name — used by the home-screen widget. */
    @Query(
        "SELECT e.id AS id, e.eventDate AS eventDate, e.title AS title, " +
            "e.description AS description, e.attendance AS attendance, e.remark AS remark, " +
            "p.fullName AS patientName, p.id AS patientId " +
            "FROM timeline_events e JOIN patients p ON p.id = e.patientId " +
            "WHERE e.eventDate BETWEEN :from AND :to ORDER BY e.eventDate ASC"
    )
    suspend fun getEventsWithPatientBetween(from: Long, to: Long): List<com.example.data.local.model.TimelineEventWithPatient>

    @Query("UPDATE timeline_events SET attendance = :attendance, updatedAt = :timestamp WHERE id = :id")
    suspend fun setAttendance(id: String, attendance: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM timeline_events WHERE patientId = :patientId")
    suspend fun getEventCountForPatient(patientId: String): Int
}
