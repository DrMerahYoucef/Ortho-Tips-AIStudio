package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chapters")
data class ChapterEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    // French text lives beside the English rather than in its own row, so bone
    // ids stay stable and bookmarks, notes and figures keep pointing at the same
    // record whichever language the app is showing. Null means "not translated",
    // which falls back to English rather than showing a blank.
    val titleFr: String? = null,
    val descriptionFr: String? = null,
    val icon: String,
    val orderIndex: Int
)

@Entity(
    tableName = "bones",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("chapterId"), Index("name"), Index("region")]
)
data class BoneEntity(
    @PrimaryKey val id: String,
    val chapterId: String,
    val name: String,
    val subtitle: String,
    val region: String,
    val traumaType: String,
    val classificationSummary: String,
    val nameFr: String? = null,
    val subtitleFr: String? = null,
    val regionFr: String? = null,
    val classificationSummaryFr: String? = null,
    val orderIndex: Int,
    val isHighYield: Boolean = true
)

@Entity(
    tableName = "sections",
    foreignKeys = [
        ForeignKey(
            entity = BoneEntity::class,
            parentColumns = ["id"],
            childColumns = ["boneId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("boneId"), Index("sectionType")]
)
data class SectionEntity(
    @PrimaryKey val id: String,
    val boneId: String,
    val sectionType: String, // ANATOMY, CLASSIFICATION, APPROACH, REDUCTION, FIXATION, PEARLS, COMPLICATIONS, REHAB
    val title: String,
    val content: String,
    val pearls: String = "", // Delimited clinical pearls
    val warnings: String = "", // Surgical danger zones
    val titleFr: String? = null,
    val contentFr: String? = null,
    val pearlsFr: String? = null,
    val warningsFr: String? = null,
    val orderIndex: Int = 0
)

@Entity(
    tableName = "images",
    foreignKeys = [
        ForeignKey(
            entity = BoneEntity::class,
            parentColumns = ["id"],
            childColumns = ["boneId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("boneId")]
)
data class ImageEntity(
    @PrimaryKey val id: String,
    val boneId: String,
    val sectionId: String? = null,
    val title: String,
    val description: String,
    val titleFr: String? = null,
    val descriptionFr: String? = null,
    val drawableResName: String = "",
    val imagePath: String? = null, // Absolute file path for images imported from a JSON database (base64 figures)
    val figureNumber: String,
    val tags: String = ""
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val boneId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val folder: String = "General",
    val tags: String = ""
)

@Entity(
    tableName = "notes",
    indices = [Index("boneId"), Index("updatedAt")]
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val boneId: String? = null,
    val title: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val colorTag: String = "teal"
)
