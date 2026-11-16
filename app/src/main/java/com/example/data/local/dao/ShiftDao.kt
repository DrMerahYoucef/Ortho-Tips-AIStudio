package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.ShiftEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ShiftDao {

    @Query("SELECT * FROM shifts ORDER BY date ASC")
    fun getAll(): Flow<List<ShiftEntity>>

    @Query("SELECT * FROM shifts WHERE date BETWEEN :from AND :to ORDER BY date ASC")
    fun getBetween(from: String, to: String): Flow<List<ShiftEntity>>

    /** Used by the home-screen widget, which cannot collect a flow. */
    @Query("SELECT * FROM shifts WHERE date >= :from ORDER BY date ASC LIMIT :limit")
    suspend fun getUpcoming(from: String, limit: Int): List<ShiftEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(shift: ShiftEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(shifts: List<ShiftEntity>)

    @Query("DELETE FROM shifts WHERE date = :date")
    suspend fun delete(date: String)
}
