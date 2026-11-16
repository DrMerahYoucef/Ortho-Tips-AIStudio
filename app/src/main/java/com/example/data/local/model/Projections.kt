package com.example.data.local.model

import com.example.data.local.entity.BoneEntity
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.ImageEntity
import com.example.data.local.entity.NoteEntity
import com.example.data.local.entity.SectionEntity

data class BoneSummary(
    val id: String,
    val chapterId: String,
    val name: String,
    val subtitle: String,
    val region: String,
    val traumaType: String,
    val classificationSummary: String,
    val isHighYield: Boolean,
    val isBookmarked: Boolean = false,
    val sectionCount: Int = 0,
    val imageCount: Int = 0,
    val noteCount: Int = 0
)

data class ChapterWithCount(
    val chapter: ChapterEntity,
    val boneCount: Int
)

data class BoneDetail(
    val bone: BoneEntity,
    val sections: List<SectionEntity>,
    val images: List<ImageEntity>,
    val notes: List<NoteEntity>,
    val isBookmarked: Boolean
)

data class SearchResultItem(
    val id: String,
    val boneId: String,
    val boneName: String,
    val region: String,
    val sectionType: String?,
    val title: String,
    val snippet: String,
    val isPearl: Boolean = false
)

data class BackupExportData(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val notes: List<NoteEntity> = emptyList(),
    val bookmarks: List<String> = emptyList()
)
