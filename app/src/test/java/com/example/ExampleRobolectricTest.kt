package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.OrthoDatabase
import com.example.data.local.entity.BoneEntity
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.NoteEntity
import com.example.data.repository.OrthoRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var database: OrthoDatabase
    private lateinit var repository: OrthoRepository

    @Test
    fun launchMainActivityWithoutCrash() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull(controller.get())
    }

    @Test
    fun verifyRealDatabaseInitializationAndQuery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val realDb = OrthoDatabase.getInstance(context)
        val chapters = realDb.chapterDao().getAllChapters().first()
        assertNotNull(chapters)
    }

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OrthoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = OrthoRepository(database)

        val testChapter = ChapterEntity(
            id = "chap_upper_limb",
            title = "Upper Limb",
            description = "Shoulder to hand",
            icon = "healing",
            orderIndex = 0
        )
        val testBone = BoneEntity(
            id = "bone_distal_radius_orif",
            chapterId = "chap_upper_limb",
            name = "Distal Radius Fracture ORIF",
            subtitle = "Volar locked plating",
            region = "Wrist",
            traumaType = "Acute",
            classificationSummary = "AO/OTA 23-C3",
            orderIndex = 0,
            isHighYield = true
        )
        database.chapterDao().insertChapters(listOf(testChapter))
        database.boneDao().insertBones(listOf(testBone))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun readAppNameFromContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Ortho Tips", appName)
    }

    @Test
    fun verifyCustomChapterAndBoneInsertion() = runBlocking {
        val chapter = ChapterEntity(
            id = "chap_lower_limb",
            title = "Lower Limb",
            description = "Pelvis to foot",
            icon = "accessibility_new",
            orderIndex = 1
        )
        val bone = BoneEntity(
            id = "bone_femur_nail",
            chapterId = "chap_lower_limb",
            name = "Femoral Shaft Antegrade Nailing",
            subtitle = "Reamed intramedullary fixation",
            region = "Thigh",
            traumaType = "Acute",
            classificationSummary = "AO/OTA 32-A/B/C",
            orderIndex = 0,
            isHighYield = true
        )
        database.chapterDao().insertChapters(listOf(chapter))
        database.boneDao().insertBones(listOf(bone))

        val chapters = repository.getAllChaptersWithCounts().first()
        assertTrue("Chapters should contain inserted chapter", chapters.any { it.chapter.id == "chap_lower_limb" })
        val highYield = repository.getHighYieldBones().first()
        assertTrue("High yield bones should contain femur", highYield.any { it.id == "bone_femur_nail" })
    }

    @Test
    fun verifyBookmarkToggle() = runBlocking {
        val boneId = "bone_distal_radius_orif"
        var bookmarkedList = repository.getBookmarkedBones().first()
        assertTrue(!bookmarkedList.any { it.id == boneId })

        repository.toggleBookmark(boneId)
        bookmarkedList = repository.getBookmarkedBones().first()
        assertTrue(bookmarkedList.any { it.id == boneId })

        repository.toggleBookmark(boneId)
        bookmarkedList = repository.getBookmarkedBones().first()
        assertTrue(!bookmarkedList.any { it.id == boneId })
    }

    @Test
    fun verifyAddAndRetrieveNotes() = runBlocking {
        val testNote = NoteEntity(
            id = "note_123",
            boneId = "bone_distal_radius_orif",
            title = "Plate screw length reminder",
            content = "Use 20mm distal screws to avoid dorsal tendon irritation.",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            colorTag = "amber"
        )
        repository.saveNote(testNote)

        val notes = repository.getAllNotes().first()
        assertEquals(1, notes.size)
        assertEquals("Plate screw length reminder", notes[0].title)
    }
}
