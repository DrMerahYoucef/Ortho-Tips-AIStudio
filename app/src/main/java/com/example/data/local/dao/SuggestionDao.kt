package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.FieldPairEntity
import com.example.data.local.entity.FieldSuggestionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SuggestionDao {

    @Query(
        "SELECT * FROM field_suggestions WHERE fieldKey = :fieldKey " +
            "ORDER BY useCount DESC, lastUsedAt DESC LIMIT :limit"
    )
    fun getSuggestions(fieldKey: String, limit: Int = 30): Flow<List<FieldSuggestionEntity>>

    @Query("SELECT useCount FROM field_suggestions WHERE fieldKey = :fieldKey AND value = :value LIMIT 1")
    suspend fun getUseCount(fieldKey: String, value: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FieldSuggestionEntity)

    @Query("DELETE FROM field_suggestions WHERE fieldKey = :fieldKey AND value = :value")
    suspend fun delete(fieldKey: String, value: String)

    /** Whole vocabulary for a field; ranking happens in SuggestionEngine. */
    @Query("SELECT * FROM field_suggestions WHERE fieldKey = :fieldKey")
    fun getAllForField(fieldKey: String): Flow<List<FieldSuggestionEntity>>

    @Query("DELETE FROM field_suggestions")
    suspend fun deleteAll()

    // ---- Learned associations between fields --------------------------------

    @Query(
        "SELECT * FROM field_pairs WHERE sourceField = :sourceField " +
            "AND sourceValue = :sourceValue AND targetField = :targetField " +
            "ORDER BY count DESC, lastUsedAt DESC LIMIT :limit"
    )
    suspend fun getPairs(
        sourceField: String,
        sourceValue: String,
        targetField: String,
        limit: Int = 12
    ): List<FieldPairEntity>

    @Query(
        "SELECT count FROM field_pairs WHERE sourceField = :sourceField " +
            "AND sourceValue = :sourceValue AND targetField = :targetField " +
            "AND targetValue = :targetValue LIMIT 1"
    )
    suspend fun getPairCount(
        sourceField: String,
        sourceValue: String,
        targetField: String,
        targetValue: String
    ): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPair(entity: FieldPairEntity)

    @Query("DELETE FROM field_pairs")
    suspend fun deleteAllPairs()
}
