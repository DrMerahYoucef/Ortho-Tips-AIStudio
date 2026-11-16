package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.SectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContentDao {
    @Query("SELECT * FROM sections WHERE boneId = :boneId ORDER BY orderIndex ASC")
    fun getSectionsForBone(boneId: String): Flow<List<SectionEntity>>

    @Query("SELECT * FROM sections WHERE boneId = :boneId AND sectionType = :type LIMIT 1")
    fun getSectionByType(boneId: String, type: String): Flow<SectionEntity?>

    @Query("SELECT * FROM sections WHERE id = :id LIMIT 1")
    suspend fun getSectionById(id: String): SectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSections(sections: List<SectionEntity>)

    @Query("SELECT * FROM sections WHERE pearls != '' ORDER BY orderIndex ASC")
    fun getAllPearls(): Flow<List<SectionEntity>>
}
