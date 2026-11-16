package com.example.data.repository

import com.example.data.local.OrthoDatabase
import com.example.data.local.entity.BookmarkEntity
import com.example.data.local.displayClassificationSummary
import com.example.data.local.displayContent
import com.example.data.local.displayName
import com.example.data.local.displayPearls
import com.example.data.local.displayRegion
import com.example.data.local.displaySubtitle
import com.example.data.local.displayTitle
import com.example.data.local.displayWarnings
import com.example.data.local.localized
import com.example.data.local.entity.BoneEntity
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.ImageEntity
import com.example.data.local.entity.NoteEntity
import com.example.data.local.entity.SectionEntity
import com.example.data.local.model.BoneDetail
import com.example.data.local.model.BoneSummary
import com.example.data.local.model.ChapterWithCount
import com.example.data.local.model.SearchResultItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [imagesDir] is where imported figures (decoded from base64) are written to disk.
 * It is optional so the repository can be unit tested without a real filesystem;
 * when null, [removeDatabase] and [reinitializeDatabase] simply skip file cleanup.
 */
class OrthoRepository(
    private val database: OrthoDatabase,
    private val imagesDir: File? = null
) {

    private val chapterDao = database.chapterDao()
    private val boneDao = database.boneDao()
    private val contentDao = database.contentDao()
    private val imageDao = database.imageDao()
    private val userDataDao = database.userDataDao()
    private val searchDao = database.searchDao()

    /**
     * The app never auto-loads sample/placeholder data. The local database starts
     * empty and only ever contains what the user has imported via a JSON database file.
     */
    fun isDatabaseEmpty(): Flow<Boolean> {
        return chapterDao.getAllChapters().map { it.isEmpty() }.flowOn(Dispatchers.IO)
    }

    suspend fun getChapterCount(): Int = withContext(Dispatchers.IO) {
        chapterDao.getChapterCount()
    }

    /**
     * Removes all imported clinical data (chapters/bones/sections/images) so the app
     * returns to the empty-database onboarding state. User notes and bookmarks are kept.
     */
    suspend fun removeDatabase() = withContext(Dispatchers.IO) {
        chapterDao.deleteAllChapters() // cascades to bones -> sections/images
        imagesDir?.let { dir -> if (dir.exists()) dir.deleteRecursively() }
    }

    /**
     * Fully resets the local database: clears clinical data like [removeDatabase], and
     * additionally clears user notes/bookmarks tied to the previously imported bones.
     */
    suspend fun reinitializeDatabase() = withContext(Dispatchers.IO) {
        chapterDao.deleteAllChapters()
        userDataDao.deleteAllNotes()
        userDataDao.deleteAllBookmarks()
        imagesDir?.let { dir -> if (dir.exists()) dir.deleteRecursively() }
    }

    fun getAllChaptersWithCounts(): Flow<List<ChapterWithCount>> {
        return combine(
            chapterDao.getAllChapters(),
            boneDao.getAllBones()
        ) { chapters, bones ->
            chapters.map { chap ->
                val count = bones.count { it.chapterId == chap.id }
                ChapterWithCount(chap.localized(), count)
            }
        }.flowOn(Dispatchers.IO)
    }

    fun getAllBonesSummary(): Flow<List<BoneSummary>> {
        return combine(
            boneDao.getAllBones(),
            userDataDao.getAllBookmarks(),
            userDataDao.getAllNotes()
        ) { bones, bookmarks, notes ->
            val bookmarkedSet = bookmarks.map { it.boneId }.toSet()
            bones.map { bone ->
                val noteCount = notes.count { it.boneId == bone.id }
                BoneSummary(
                    id = bone.id,
                    chapterId = bone.chapterId,
                    name = bone.displayName,
                    subtitle = bone.displaySubtitle,
                    region = bone.displayRegion,
                    traumaType = bone.traumaType,
                    classificationSummary = bone.displayClassificationSummary,
                    isHighYield = bone.isHighYield,
                    isBookmarked = bookmarkedSet.contains(bone.id),
                    noteCount = noteCount
                )
            }
        }.flowOn(Dispatchers.IO)
    }

    fun getBonesByChapter(chapterId: String): Flow<List<BoneSummary>> {
        return combine(
            boneDao.getBonesByChapter(chapterId),
            userDataDao.getAllBookmarks(),
            userDataDao.getAllNotes()
        ) { bones, bookmarks, notes ->
            val bookmarkedSet = bookmarks.map { it.boneId }.toSet()
            bones.map { bone ->
                val noteCount = notes.count { it.boneId == bone.id }
                BoneSummary(
                    id = bone.id,
                    chapterId = bone.chapterId,
                    name = bone.displayName,
                    subtitle = bone.displaySubtitle,
                    region = bone.displayRegion,
                    traumaType = bone.traumaType,
                    classificationSummary = bone.displayClassificationSummary,
                    isHighYield = bone.isHighYield,
                    isBookmarked = bookmarkedSet.contains(bone.id),
                    noteCount = noteCount
                )
            }
        }.flowOn(Dispatchers.IO)
    }

    fun getHighYieldBones(): Flow<List<BoneSummary>> {
        return combine(
            boneDao.getHighYieldBones(),
            userDataDao.getAllBookmarks(),
            userDataDao.getAllNotes()
        ) { bones, bookmarks, notes ->
            val bookmarkedSet = bookmarks.map { it.boneId }.toSet()
            bones.map { bone ->
                val noteCount = notes.count { it.boneId == bone.id }
                BoneSummary(
                    id = bone.id,
                    chapterId = bone.chapterId,
                    name = bone.displayName,
                    subtitle = bone.displaySubtitle,
                    region = bone.displayRegion,
                    traumaType = bone.traumaType,
                    classificationSummary = bone.displayClassificationSummary,
                    isHighYield = bone.isHighYield,
                    isBookmarked = bookmarkedSet.contains(bone.id),
                    noteCount = noteCount
                )
            }
        }.flowOn(Dispatchers.IO)
    }

    fun getBookmarkedBones(): Flow<List<BoneSummary>> {
        return combine(
            boneDao.getAllBones(),
            userDataDao.getAllBookmarks(),
            userDataDao.getAllNotes()
        ) { bones, bookmarks, notes ->
            val bookmarkedSet = bookmarks.map { it.boneId }.toSet()
            val noteMap = notes.groupBy { it.boneId }
            bones.filter { bookmarkedSet.contains(it.id) }.map { bone ->
                BoneSummary(
                    id = bone.id,
                    chapterId = bone.chapterId,
                    name = bone.displayName,
                    subtitle = bone.displaySubtitle,
                    region = bone.displayRegion,
                    traumaType = bone.traumaType,
                    classificationSummary = bone.displayClassificationSummary,
                    isHighYield = bone.isHighYield,
                    isBookmarked = true,
                    noteCount = noteMap[bone.id]?.size ?: 0
                )
            }
        }.flowOn(Dispatchers.IO)
    }

    fun getBoneDetail(boneId: String): Flow<BoneDetail?> {
        return combine(
            boneDao.getBoneById(boneId),
            contentDao.getSectionsForBone(boneId),
            imageDao.getImagesForBone(boneId),
            userDataDao.getNotesForBone(boneId),
            userDataDao.isBoneBookmarked(boneId)
        ) { bone, sections, images, notes, isBookmarked ->
            if (bone == null) null
            else BoneDetail(
                bone = bone.localized(),
                sections = sections.map { it.localized() },
                images = images.map { it.localized() },
                notes = notes,
                isBookmarked = isBookmarked
            )
        }.flowOn(Dispatchers.IO)
    }

    suspend fun getChapter(chapterId: String): ChapterEntity? = withContext(Dispatchers.IO) {
        chapterDao.getChapterById(chapterId)?.localized()
    }

    suspend fun toggleBookmark(boneId: String) = withContext(Dispatchers.IO) {
        val bookmarks = userDataDao.getAllBookmarkedBoneIds()
        if (bookmarks.contains(boneId)) {
            userDataDao.deleteBookmark(boneId)
        } else {
            userDataDao.insertBookmark(BookmarkEntity(boneId = boneId))
        }
    }

    fun getAllNotes(): Flow<List<NoteEntity>> = userDataDao.getAllNotes().flowOn(Dispatchers.IO)

    suspend fun saveNote(note: NoteEntity) = withContext(Dispatchers.IO) {
        userDataDao.insertNote(note)
    }

    suspend fun deleteNote(noteId: String) = withContext(Dispatchers.IO) {
        userDataDao.deleteNoteById(noteId)
    }

    fun getAllImages(): Flow<List<ImageEntity>> =
        imageDao.getAllImages().map { list -> list.map { it.localized() } }.flowOn(Dispatchers.IO)

    /**
     * One-shot snapshot of every figure that belongs to [boneId], ordered the same way
     * as [getAllImages] for a bone. Used by the full-screen image viewer to build its
     * swipeable pager starting from whichever figure the user tapped.
     */
    suspend fun getImagesForBoneOnce(boneId: String): List<ImageEntity> = withContext(Dispatchers.IO) {
        imageDao.getImagesForBone(boneId).first().map { it.localized() }
    }

    suspend fun getImageById(imageId: String): ImageEntity? = withContext(Dispatchers.IO) {
        imageDao.getImageById(imageId)?.localized()
    }

    fun searchAll(query: String): Flow<List<SearchResultItem>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return getHighYieldBones().map { list ->
                list.map {
                    // BoneSummary, not BoneEntity: getHighYieldBones already
                    // resolved the language when it built these, so reading the
                    // plain fields here is both correct and already French.
                    SearchResultItem(
                        id = it.id,
                        boneId = it.id,
                        boneName = it.name,
                        region = it.region,
                        sectionType = null,
                        title = it.subtitle,
                        snippet = it.classificationSummary,
                        isPearl = false
                    )
                }
            }.flowOn(Dispatchers.IO)
        }

        return combine(
            searchDao.searchBones(trimmed),
            searchDao.searchSections(trimmed),
            boneDao.getAllBones()
        ) { boneMatches, sectionMatches, allBones ->
            val boneMap = allBones.associateBy { it.id }
            val results = mutableListOf<SearchResultItem>()

            // Add bone matches first
            boneMatches.forEach { bone ->
                results.add(
                    SearchResultItem(
                        id = "b_${bone.id}",
                        boneId = bone.id,
                        boneName = bone.displayName,
                        region = bone.displayRegion,
                        sectionType = null,
                        title = bone.displayName,
                        snippet = "${bone.displaySubtitle} • ${bone.displayClassificationSummary}",
                        isPearl = false
                    )
                )
            }

            // Add section matches
            sectionMatches.forEach { sec ->
                val parent = boneMap[sec.boneId]
                val isPearlMatch = sec.displayPearls.contains(trimmed, ignoreCase = true)
                val snippetText = if (isPearlMatch) {
                    sec.displayPearls
                } else if (sec.displayWarnings.contains(trimmed, ignoreCase = true)) {
                    "⚠️ ${sec.displayWarnings}"
                } else {
                    sec.displayContent.take(150) + "..."
                }

                results.add(
                    SearchResultItem(
                        id = "s_${sec.id}",
                        boneId = sec.boneId,
                        boneName = parent?.name ?: "Procedure",
                        region = parent?.region ?: "General",
                        sectionType = sec.sectionType,
                        title = sec.displayTitle,
                        snippet = snippetText,
                        isPearl = isPearlMatch
                    )
                )
            }

            results
        }.flowOn(Dispatchers.IO)
    }

    suspend fun insertCustomBoneWithSections(
        bone: BoneEntity,
        sections: List<SectionEntity>
    ) = withContext(Dispatchers.IO) {
        boneDao.insertBones(listOf(bone))
        contentDao.insertSections(sections)
    }
}
