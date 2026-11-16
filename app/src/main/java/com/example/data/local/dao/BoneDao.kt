package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.BoneEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BoneDao {
    @Query("SELECT * FROM bones ORDER BY orderIndex ASC")
    fun getAllBones(): Flow<List<BoneEntity>>

    @Query("SELECT * FROM bones WHERE chapterId = :chapterId ORDER BY orderIndex ASC")
    fun getBonesByChapter(chapterId: String): Flow<List<BoneEntity>>

    @Query("SELECT * FROM bones WHERE region = :region ORDER BY orderIndex ASC")
    fun getBonesByRegion(region: String): Flow<List<BoneEntity>>

    @Query("SELECT * FROM bones WHERE isHighYield = 1 ORDER BY orderIndex ASC")
    fun getHighYieldBones(): Flow<List<BoneEntity>>

    @Query("SELECT * FROM bones WHERE id = :id LIMIT 1")
    fun getBoneById(id: String): Flow<BoneEntity?>

    @Query("SELECT * FROM bones WHERE id = :id LIMIT 1")
    suspend fun getBoneDirect(id: String): BoneEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBones(bones: List<BoneEntity>)

    @Query("SELECT COUNT(*) FROM bones")
    suspend fun getBoneCount(): Int

    @Query("SELECT DISTINCT region FROM bones ORDER BY region ASC")
    fun getAllRegions(): Flow<List<String>>
}
