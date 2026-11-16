package com.example.data.sync

import com.example.data.local.OrthoDatabase
import com.example.data.telegram.DriveLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Serialises every user-owned Room table to JSON and restores it again.
 *
 * Deliberately generic — it reads the table list out of `sqlite_master` and walks
 * columns by cursor metadata rather than through typed DAOs. Two reasons:
 *
 *  1. It covers every table the app has, including ones added later, without a
 *     matching code change in the sync layer. Patients, surgeries, timeline
 *     events, clinical notes, bookmarks, learned suggestions and the imported
 *     bone atlas are all carried by the same mechanism.
 *  2. It cannot drift out of step with the entity classes, which is exactly the
 *     failure mode that turns a restore into silent data loss.
 *
 * Restore is INSERT OR REPLACE inside one transaction, per-row keyed by the
 * table's own primary key, so a partial snapshot merges instead of clobbering.
 */
class DatabaseSnapshot(private val database: OrthoDatabase) {

    /** Tables owned by the sync layer itself; snapshotting them would recurse. */
    private val excluded = setOf(
        "remote_objects",
        "transfer_queue",
        "sync_tombstones",
        "room_master_table",
        "sqlite_sequence",
        "android_metadata"
    )

    suspend fun export(destination: File): SnapshotSummary = withContext(Dispatchers.IO) {
        // Reads go through Room's own connection rather than openHelper's, so an
        // export always sees what the DAOs just wrote. Reading straight off
        // openHelper can hand back a connection Room is not writing through.
        val db = database.openHelper.writableDatabase
        DriveLog.d(TAG, "Exporting from ${db.path}")
        val root = JSONObject()
        root.put("format", FORMAT_VERSION)
        root.put("createdAt", System.currentTimeMillis())

        val tables = JSONObject()
        var totalRows = 0
        val perTable = mutableListOf<String>()
        for (table in tableNames()) {
            val rows = JSONArray()
            db.query("SELECT * FROM `$table`").use { cursor ->
                val columns = cursor.columnNames
                while (cursor.moveToNext()) {
                    val row = JSONObject()
                    columns.forEachIndexed { index, column ->
                        when {
                            cursor.isNull(index) -> row.put(column, JSONObject.NULL)
                            else -> row.put(column, cursor.getString(index))
                        }
                    }
                    rows.put(row)
                    totalRows++
                }
            }
            tables.put(table, rows)
            if (rows.length() > 0) perTable += "$table=${rows.length()}"
        }
        root.put("tables", tables)
        DriveLog.d(
            TAG,
            if (perTable.isEmpty()) "Every table read back empty"
            else "Row counts: ${perTable.joinToString(", ")}"
        )

        destination.parentFile?.mkdirs()
        destination.writeText(root.toString())
        DriveLog.i(TAG, "Snapshot exported: $totalRows rows across ${tables.length()} tables")
        SnapshotSummary(
            tableCount = tables.length(),
            rowCount = totalRows,
            sizeBytes = destination.length(),
            createdAt = root.getLong("createdAt")
        )
    }

    /**
     * Merges [source] into the live database. Existing rows with the same primary
     * key are replaced; rows present locally but absent from the snapshot are
     * kept, because a snapshot taken on another device before a local capture
     * must never erase that capture.
     */
    suspend fun import(source: File): SnapshotSummary = withContext(Dispatchers.IO) {
        require(source.exists()) { "Snapshot file does not exist" }
        val root = JSONObject(source.readText())
        val format = root.optInt("format", 0)
        require(format <= FORMAT_VERSION) {
            "Snapshot was written by a newer version of Ortho Tips (format $format)"
        }
        val tables = root.optJSONObject("tables") ?: JSONObject()
        val known = tableNames().toSet()
        val db = database.openHelper.writableDatabase
        DriveLog.d(TAG, "Importing into ${db.path}; schema has ${known.size} tables")

        var totalRows = 0
        var importedTables = 0
        db.beginTransaction()
        try {
            // Children are inserted before their parents unless the order is
            // fixed, and Room enforces foreign keys, so the first patient_images
            // row would fail and roll the whole import back. Deferring the checks
            // to commit time makes insertion order irrelevant; the ordering below
            // is belt and braces for anything the pragma does not cover.
            db.execSQL("PRAGMA defer_foreign_keys = TRUE")

            for (table in insertionOrder(tables.keys().asSequence().toList())) {
                if (table !in known) {
                    DriveLog.w(TAG, "Snapshot table '$table' is not in this schema; skipped")
                    continue
                }
                val rows = tables.optJSONArray(table) ?: continue
                if (rows.length() == 0) continue
                importedTables++
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val columns = row.keys().asSequence().toList()
                    if (columns.isEmpty()) continue
                    val placeholders = columns.joinToString(",") { "?" }
                    val columnList = columns.joinToString(",") { "`$it`" }
                    val values = columns.map { column ->
                        if (row.isNull(column)) null else row.getString(column)
                    }.toTypedArray()
                    try {
                        db.execSQL(
                            "INSERT OR REPLACE INTO `$table` ($columnList) VALUES ($placeholders)",
                            values
                        )
                        totalRows++
                    } catch (e: Exception) {
                        // Naming the table matters: a silent rollback here is what
                        // made this look like an empty database rather than a
                        // failed import.
                        DriveLog.e(TAG, "Row $i of '$table' could not be imported", e)
                        throw e
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        DriveLog.i(TAG, "Snapshot imported: $totalRows rows into $importedTables tables")
        // Immediately read one row back. If this says 0 after a non-zero import,
        // the write is not reaching the connection the rest of the app reads from.
        runCatching {
            db.query("SELECT COUNT(*) FROM `patients`").use { c ->
                if (c.moveToFirst()) DriveLog.i(TAG, "patients now holds ${c.getInt(0)} rows")
            }
        }
        SnapshotSummary(
            tableCount = importedTables,
            rowCount = totalRows,
            sizeBytes = source.length(),
            createdAt = root.optLong("createdAt", 0)
        )
    }

    /** Reads snapshot metadata without applying it, for the conflict prompt. */
    suspend fun inspect(source: File): SnapshotSummary? = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(source.readText())
            val tables = root.optJSONObject("tables") ?: JSONObject()
            var rows = 0
            tables.keys().forEach { rows += tables.optJSONArray(it)?.length() ?: 0 }
            SnapshotSummary(
                tableCount = tables.length(),
                rowCount = rows,
                sizeBytes = source.length(),
                createdAt = root.optLong("createdAt", 0)
            )
        }.getOrNull()
    }

    /**
     * Orders tables so a parent is always written before anything that points at
     * it. `tableNames()` is alphabetical, which puts `patient_documents` and
     * `patient_images` ahead of `patients` — the exact order that made every
     * import fail on a foreign key and roll back silently.
     *
     * Tables not named here keep their relative order and go last; they are
     * either standalone or covered by the deferred-key pragma.
     */
    private fun insertionOrder(tables: List<String>): List<String> {
        val parentsFirst = listOf(
            "chapters",
            "bones",
            "sections",
            "images",
            "patients",
            "timeline_events",
            "surgeries",
            "patient_images",
            "patient_documents"
        )
        val ranked = tables.filter { it in parentsFirst }.sortedBy { parentsFirst.indexOf(it) }
        val rest = tables.filterNot { it in parentsFirst }
        return ranked + rest
    }

    private fun tableNames(): List<String> {
        val db = database.openHelper.writableDatabase
        val names = mutableListOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
                    "AND name NOT LIKE 'room_%'"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                if (name !in excluded) names.add(name)
            }
        }
        return names.sorted()
    }

    companion object {
        private const val TAG = "DatabaseSnapshot"
        const val FORMAT_VERSION = 1
    }
}

data class SnapshotSummary(
    val tableCount: Int,
    val rowCount: Int,
    val sizeBytes: Long,
    val createdAt: Long
)
