package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.PatientDocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PatientDocumentDao {

    @Query("SELECT * FROM patient_documents WHERE patientId = :patientId ORDER BY createdAt DESC")
    fun getDocumentsForPatient(patientId: String): Flow<List<PatientDocumentEntity>>

    @Query("SELECT * FROM patient_documents WHERE timelineEventId = :eventId ORDER BY createdAt ASC")
    fun getDocumentsForEvent(eventId: String): Flow<List<PatientDocumentEntity>>

    @Query("SELECT * FROM patient_documents WHERE surgeryId = :surgeryId ORDER BY createdAt ASC")
    fun getDocumentsForSurgery(surgeryId: String): Flow<List<PatientDocumentEntity>>

    @Query("SELECT * FROM patient_documents WHERE id = :id LIMIT 1")
    suspend fun getDocumentDirect(id: String): PatientDocumentEntity?

    @Query("SELECT * FROM patient_documents WHERE patientId = :patientId")
    suspend fun getDocumentsForPatientDirect(patientId: String): List<PatientDocumentEntity>

    @Query(
        "SELECT * FROM patient_documents WHERE ocrText != '' AND ocrText LIKE '%' || :query || '%' ORDER BY createdAt DESC"
    )
    fun searchByOcrText(query: String): Flow<List<PatientDocumentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: PatientDocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocuments(documents: List<PatientDocumentEntity>)

    @Update
    suspend fun updateDocument(document: PatientDocumentEntity)

    @Query("DELETE FROM patient_documents WHERE id = :id")
    suspend fun deleteDocument(id: String)

    @Query("SELECT COUNT(*) FROM patient_documents WHERE patientId = :patientId")
    suspend fun getDocumentCountForPatient(patientId: String): Int
}
