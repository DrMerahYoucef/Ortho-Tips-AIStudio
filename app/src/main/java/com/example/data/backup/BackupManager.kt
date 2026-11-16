package com.example.data.backup

import android.content.Context
import com.example.data.local.OrthoDatabase
import com.example.data.local.entity.BookmarkEntity
import com.example.data.local.entity.NoteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class BackupManager(
    private val context: Context,
    private val database: OrthoDatabase
) {
    suspend fun exportDataJson(): String = withContext(Dispatchers.IO) {
        val notes = database.userDataDao().getAllNotesDirect()
        val bookmarks = database.userDataDao().getAllBookmarkedBoneIds()

        val root = JSONObject()
        root.put("version", 1)
        root.put("timestamp", System.currentTimeMillis())

        val notesArray = JSONArray()
        for (note in notes) {
            val noteObj = JSONObject()
            noteObj.put("id", note.id)
            noteObj.put("boneId", note.boneId ?: "")
            noteObj.put("title", note.title)
            noteObj.put("content", note.content)
            noteObj.put("createdAt", note.createdAt)
            noteObj.put("updatedAt", note.updatedAt)
            noteObj.put("colorTag", note.colorTag)
            notesArray.put(noteObj)
        }
        root.put("notes", notesArray)

        val bookmarksArray = JSONArray()
        for (bm in bookmarks) {
            bookmarksArray.put(bm)
        }
        root.put("bookmarks", bookmarksArray)

        root.toString(2)
    }

    suspend fun importDataJson(jsonString: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(jsonString)
            var count = 0

            if (root.has("notes")) {
                val notesArray = root.getJSONArray("notes")
                for (i in 0 until notesArray.length()) {
                    val noteObj = notesArray.getJSONObject(i)
                    val note = NoteEntity(
                        id = noteObj.optString("id", java.util.UUID.randomUUID().toString()),
                        boneId = noteObj.optString("boneId").ifEmpty { null },
                        title = noteObj.optString("title", "Imported Note"),
                        content = noteObj.optString("content", ""),
                        createdAt = noteObj.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = noteObj.optLong("updatedAt", System.currentTimeMillis()),
                        colorTag = noteObj.optString("colorTag", "teal")
                    )
                    database.userDataDao().insertNote(note)
                    count++
                }
            }

            if (root.has("bookmarks")) {
                val bookmarksArray = root.getJSONArray("bookmarks")
                for (i in 0 until bookmarksArray.length()) {
                    val boneId = bookmarksArray.getString(i)
                    database.userDataDao().insertBookmark(BookmarkEntity(boneId = boneId))
                    count++
                }
            }

            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
