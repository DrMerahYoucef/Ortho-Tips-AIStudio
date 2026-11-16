package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.example.data.local.entity.BoneEntity
import com.example.data.local.entity.SectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchDao {
    @Query("""
        SELECT * FROM bones 
        WHERE name LIKE '%' || :query || '%' 
           OR subtitle LIKE '%' || :query || '%'
           OR region LIKE '%' || :query || '%'
           OR traumaType LIKE '%' || :query || '%'
           OR classificationSummary LIKE '%' || :query || '%'
           -- French text is searched alongside the English rather than instead
           -- of it. A surgeon working in French still knows the English terms,
           -- and matching both means a search never comes back empty just
           -- because the query was typed in the other language.
           OR IFNULL(nameFr, '') LIKE '%' || :query || '%'
           OR IFNULL(subtitleFr, '') LIKE '%' || :query || '%'
           OR IFNULL(regionFr, '') LIKE '%' || :query || '%'
           OR IFNULL(classificationSummaryFr, '') LIKE '%' || :query || '%'
        ORDER BY 
            CASE 
                WHEN name LIKE :query || '%' THEN 1
                WHEN name LIKE '%' || :query || '%' THEN 2
                ELSE 3 
            END,
            orderIndex ASC
    """)
    fun searchBones(query: String): Flow<List<BoneEntity>>

    @Query("""
        SELECT * FROM sections 
        WHERE title LIKE '%' || :query || '%' 
           OR content LIKE '%' || :query || '%'
           OR pearls LIKE '%' || :query || '%'
           OR warnings LIKE '%' || :query || '%'
        ORDER BY orderIndex ASC
    """)
    fun searchSections(query: String): Flow<List<SectionEntity>>
}
