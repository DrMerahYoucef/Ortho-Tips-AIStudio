package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.PatientImageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PatientImageDao {

    @Query("SELECT * FROM patient_images WHERE patientId = :patientId ORDER BY capturedAt DESC")
    fun getImagesForPatient(patientId: String): Flow<List<PatientImageEntity>>

    @Query("SELECT * FROM patient_images WHERE patientId = :patientId AND category = :category ORDER BY capturedAt DESC")
    fun getImagesForPatientByCategory(patientId: String, category: String): Flow<List<PatientImageEntity>>

    @Query("SELECT * FROM patient_images WHERE timelineEventId = :eventId ORDER BY capturedAt ASC")
    fun getImagesForEvent(eventId: String): Flow<List<PatientImageEntity>>

    @Query("SELECT * FROM patient_images WHERE surgeryId = :surgeryId ORDER BY capturedAt ASC")
    fun getImagesForSurgery(surgeryId: String): Flow<List<PatientImageEntity>>

    @Query("SELECT * FROM patient_images WHERE id = :id LIMIT 1")
    fun getImageById(id: String): Flow<PatientImageEntity?>

    @Query("SELECT * FROM patient_images WHERE id = :id LIMIT 1")
    suspend fun getImageDirect(id: String): PatientImageEntity?

    @Query("SELECT * FROM patient_images WHERE patientId = :patientId")
    suspend fun getImagesForPatientDirect(patientId: String): List<PatientImageEntity>

    @Query(
        "SELECT * FROM patient_images WHERE ocrText != '' AND ocrText LIKE '%' || :query || '%' ORDER BY capturedAt DESC"
    )
    fun searchByOcrText(query: String): Flow<List<PatientImageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImage(image: PatientImageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImages(images: List<PatientImageEntity>)

    @Update
    suspend fun updateImage(image: PatientImageEntity)

    @Query("DELETE FROM patient_images WHERE id = :id")
    suspend fun deleteImage(id: String)

    @Query("SELECT COUNT(*) FROM patient_images WHERE patientId = :patientId")
    suspend fun getImageCountForPatient(patientId: String): Int
}
