package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.SurgeryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SurgeryDao {

    @Query("SELECT * FROM surgeries WHERE patientId = :patientId ORDER BY surgeryDate DESC")
    fun getSurgeriesForPatient(patientId: String): Flow<List<SurgeryEntity>>

    @Query("SELECT * FROM surgeries WHERE id = :id LIMIT 1")
    fun getSurgeryById(id: String): Flow<SurgeryEntity?>

    @Query("SELECT * FROM surgeries WHERE id = :id LIMIT 1")
    suspend fun getSurgeryDirect(id: String): SurgeryEntity?

    @Query(
        """
        SELECT * FROM surgeries
        WHERE procedure LIKE '%' || :query || '%' OR diagnosis LIKE '%' || :query || '%'
        ORDER BY surgeryDate DESC
        """
    )
    fun searchSurgeries(query: String): Flow<List<SurgeryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSurgery(surgery: SurgeryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSurgeries(surgeries: List<SurgeryEntity>)

    @Update
    suspend fun updateSurgery(surgery: SurgeryEntity)

    @Query("DELETE FROM surgeries WHERE id = :id")
    suspend fun deleteSurgery(id: String)
}
