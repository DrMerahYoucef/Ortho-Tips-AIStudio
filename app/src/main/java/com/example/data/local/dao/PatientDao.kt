package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.PatientEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PatientDao {

    @Query("SELECT * FROM patients WHERE isArchived = 0 ORDER BY fullName ASC")
    fun getActivePatients(): Flow<List<PatientEntity>>

    @Query("SELECT * FROM patients WHERE isArchived = 1 ORDER BY updatedAt DESC")
    fun getArchivedPatients(): Flow<List<PatientEntity>>

    @Query("SELECT * FROM patients ORDER BY fullName ASC")
    fun getAllPatients(): Flow<List<PatientEntity>>

    @Query("SELECT * FROM patients WHERE id = :id LIMIT 1")
    fun getPatientById(id: String): Flow<PatientEntity?>

    @Query("SELECT * FROM patients WHERE id = :id LIMIT 1")
    suspend fun getPatientDirect(id: String): PatientEntity?

    @Query("SELECT * FROM patients WHERE id IN (:ids)")
    suspend fun getPatientsByIds(ids: List<String>): List<PatientEntity>

    @Query(
        """
        SELECT * FROM patients
        WHERE isArchived = 0 AND (
            fullName LIKE '%' || :query || '%' OR
            patientCode LIKE '%' || :query || '%' OR
            diagnosis LIKE '%' || :query || '%' OR
            fractureClassification LIKE '%' || :query || '%'
        )
        ORDER BY fullName ASC
        """
    )
    fun searchPatients(query: String): Flow<List<PatientEntity>>

    @Query("SELECT id FROM patients")
    suspend fun getAllPatientIds(): List<String>

    @Query("SELECT COUNT(*) FROM patients")
    suspend fun getPatientCount(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM patients WHERE patientCode = :patientCode AND id != :excludeId)")
    suspend fun isPatientCodeTaken(patientCode: String, excludeId: String = ""): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPatient(patient: PatientEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPatients(patients: List<PatientEntity>)

    /** Returns how many rows changed, so callers know whether to insert instead. */
    @Update
    suspend fun updatePatient(patient: PatientEntity): Int

    @Query("UPDATE patients SET isArchived = :archived, updatedAt = :timestamp WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE patients SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchUpdatedAt(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM patients WHERE id = :id")
    suspend fun deletePatient(id: String)

    @Query("DELETE FROM patients WHERE id IN (:ids)")
    suspend fun deletePatients(ids: List<String>)

    @Query("DELETE FROM patients")
    suspend fun deleteAllPatients()
}
