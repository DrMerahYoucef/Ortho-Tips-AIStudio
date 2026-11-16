package com.example.data.sync

import android.database.Cursor
import android.util.Base64
import com.example.data.crypto.Hashing
import com.example.data.local.OrthoDatabase
import com.example.data.storage.StorageKey
import com.example.data.telegram.DriveLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Serialises strictly the patient-domain Room tables to JSON and restores them.
 *
 * Non-patient tables — on-call shifts (`shifts`), anatomical reference books and
 * figures (`chapters`, `bones`, `sections`, `images`), study notes/bookmarks
 * (`notes`, `bookmarks`), learned autocomplete suggestions (`field_suggestions`,
 * `field_pairs`), and sync internal tables — are intentionally excluded from both
 * export and import.
 *
 * Filtering on both sides also protects against older legacy snapshots already stored
 * in a Telegram channel: restoring an earlier full-DB snapshot extracts only the
 * patient tables and leaves local shifts and anatomical reference data untouched.
 *
 * Restore is INSERT OR REPLACE inside one transaction, per-row keyed by each
 * table's primary key, so snapshots merge rather than clobbering newer captures.
 */
class DatabaseSnapshot(
    private val database: OrthoDatabase,
    private val patientMediaRootDir: File? = null,
    private val onRecordsImported: (() -> Unit)? = null
) {

    /** Returns how many patient rows currently exist locally. */
    suspend fun localPatientCount(): Int = withContext(Dispatchers.IO) {
        val db = database.openHelper.readableDatabase
        runCatching {
            db.query("SELECT COUNT(*) FROM `patients`").use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        }.getOrDefault(0)
    }

    suspend fun export(destination: File): SnapshotSummary = withContext(Dispatchers.IO) {
        val db = database.openHelper.writableDatabase
        DriveLog.d(TAG, "Exporting patient records from ${db.path}")
        val createdAt = System.currentTimeMillis()
        val root = JSONObject()
        root.put("format", FORMAT_VERSION)
        root.put("createdAt", createdAt)
        root.put("scope", SCOPE_PATIENTS_ONLY)

        val existingTables = schemaTableNames()
        val targetTables = PATIENT_TABLES.filter { it in existingTables }

        val tables = JSONObject()
        var totalRows = 0
        var patientCount = 0
        var patientMediaCount = 0
        val perTable = mutableListOf<String>()

        for (table in targetTables) {
            val rows = JSONArray()
            db.query("SELECT * FROM `$table`").use { cursor ->
                val columns = cursor.columnNames
                while (cursor.moveToNext()) {
                    val row = JSONObject()
                    columns.forEachIndexed { index, column ->
                        when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> row.put(column, JSONObject.NULL)
                            Cursor.FIELD_TYPE_INTEGER -> row.put(column, cursor.getLong(index))
                            Cursor.FIELD_TYPE_FLOAT -> row.put(column, cursor.getDouble(index))
                            Cursor.FIELD_TYPE_BLOB -> row.put(
                                column,
                                BLOB_PREFIX + Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP)
                            )
                            else -> row.put(column, cursor.getString(index))
                        }
                    }
                    rows.put(row)
                    totalRows++
                }
            }
            tables.put(table, rows)
            when (table) {
                "patients" -> patientCount = rows.length()
                "patient_images", "patient_documents" -> patientMediaCount += rows.length()
            }
            if (rows.length() > 0) perTable += "$table=${rows.length()}"
        }
        root.put("tables", tables)
        DriveLog.d(
            TAG,
            if (perTable.isEmpty()) "No patient records present locally"
            else "Patient table counts: ${perTable.joinToString(", ")}"
        )

        destination.parentFile?.mkdirs()
        destination.writeText(root.toString())
        DriveLog.i(
            TAG,
            "Patient snapshot exported: $totalRows rows across ${tables.length()} patient tables ($patientCount patients)"
        )
        SnapshotSummary(
            tableCount = tables.length(),
            rowCount = totalRows,
            patientCount = patientCount,
            patientMediaCount = patientMediaCount,
            sizeBytes = destination.length(),
            createdAt = createdAt,
            contentHash = canonicalTablesHash(tables)
        )
    }

    /**
     * Merges only the patient-domain tables from [source] into the live database.
     * Any non-patient tables (e.g., shifts, chapters, bones in legacy snapshots) are
     * ignored completely.
     */
    suspend fun import(source: File): SnapshotSummary = withContext(Dispatchers.IO) {
        require(source.exists()) { "Snapshot file does not exist" }
        val root = JSONObject(source.readText())
        val format = root.optInt("format", 0)
        require(format <= FORMAT_VERSION) {
            "Snapshot was written by a newer version of Ortho Tips (format $format)"
        }
        val rawTables = root.optJSONObject("tables") ?: JSONObject()
        val patientTablesOnly = extractPatientTables(rawTables)
        val known = schemaTableNames().toSet()
        val db = database.openHelper.writableDatabase
        DriveLog.d(TAG, "Importing patient records into ${db.path}")

        var totalRows = 0
        var importedTables = 0
        var importedPatients = 0
        var importedMedia = 0

        db.beginTransaction()
        try {
            db.execSQL("PRAGMA defer_foreign_keys = TRUE")

            for (table in PATIENT_TABLES) {
                if (table !in known) {
                    DriveLog.w(TAG, "Snapshot table '$table' is not in this schema; skipped")
                    continue
                }
                val rows = patientTablesOnly.optJSONArray(table) ?: continue
                if (rows.length() == 0) continue
                importedTables++
                when (table) {
                    "patients" -> importedPatients = rows.length()
                    "patient_images", "patient_documents" -> importedMedia += rows.length()
                }
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val columns = row.keys().asSequence().toList()
                    if (columns.isEmpty()) continue
                    val placeholders = columns.joinToString(",") { "?" }
                    val columnList = columns.joinToString(",") { "`$it`" }
                    val values = columns.map { column ->
                        val decoded = decodeValue(row, column)
                        if (table in MEDIA_PATH_TABLES && (column == "filePath" || column == "thumbnailPath")) {
                            normalizeMediaPath(decoded as? String)
                        } else {
                            decoded
                        }
                    }.toTypedArray()
                    try {
                        db.execSQL(
                            "INSERT OR REPLACE INTO `$table` ($columnList) VALUES ($placeholders)",
                            values
                        )
                        totalRows++
                    } catch (e: Exception) {
                        DriveLog.e(TAG, "Row $i of '$table' could not be imported", e)
                        throw e
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        if (totalRows > 0) {
            runCatching { database.invalidationTracker.refreshVersionsAsync() }
            runCatching { onRecordsImported?.invoke() }
        }

        DriveLog.i(
            TAG,
            "Patient snapshot imported: $totalRows rows ($importedPatients patients, $importedMedia media links) into $importedTables tables"
        )
        SnapshotSummary(
            tableCount = importedTables,
            rowCount = totalRows,
            patientCount = importedPatients,
            patientMediaCount = importedMedia,
            sizeBytes = source.length(),
            createdAt = root.optLong("createdAt", 0),
            contentHash = canonicalTablesHash(patientTablesOnly)
        )
    }

    /**
     * Reads patient-domain metadata without applying it, accurately ignoring
     * legacy reference/shift tables when deciding whether a snapshot carries patients.
     */
    suspend fun inspect(source: File): SnapshotSummary? = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(source.readText())
            val rawTables = root.optJSONObject("tables") ?: JSONObject()
            val patientTablesOnly = extractPatientTables(rawTables)
            var rows = 0
            var nonEmptyTables = 0
            for (table in PATIENT_TABLES) {
                val len = patientTablesOnly.optJSONArray(table)?.length() ?: 0
                if (len > 0) nonEmptyTables++
                rows += len
            }
            val patients = patientTablesOnly.optJSONArray("patients")?.length() ?: 0
            val media = (patientTablesOnly.optJSONArray("patient_images")?.length() ?: 0) +
                (patientTablesOnly.optJSONArray("patient_documents")?.length() ?: 0)
            SnapshotSummary(
                tableCount = nonEmptyTables,
                rowCount = rows,
                patientCount = patients,
                patientMediaCount = media,
                sizeBytes = source.length(),
                createdAt = root.optLong("createdAt", 0),
                contentHash = canonicalTablesHash(patientTablesOnly)
            )
        }.getOrNull()
    }

    private fun extractPatientTables(rawTables: JSONObject): JSONObject {
        val filtered = JSONObject()
        for (table in PATIENT_TABLES) {
            rawTables.optJSONArray(table)?.let { filtered.put(table, it) }
        }
        return filtered
    }

    private fun normalizeMediaPath(rawPath: String?): String? {
        if (rawPath.isNullOrBlank()) return rawPath
        val root = patientMediaRootDir ?: return rawPath
        val normalized = rawPath.replace(File.separatorChar, '/')
        val marker = "/${StorageKey.PATIENTS_PREFIX}"
        val rel = when {
            normalized.startsWith(StorageKey.PATIENTS_PREFIX) -> normalized
            normalized.contains(marker) -> StorageKey.PATIENTS_PREFIX + normalized.substringAfter(marker)
            else -> return rawPath
        }
        return File(root, rel).absolutePath
    }

    private fun decodeValue(row: JSONObject, column: String): Any? {
        if (row.isNull(column)) return null
        return when (val raw = row.get(column)) {
            is String -> if (raw.startsWith(BLOB_PREFIX)) {
                Base64.decode(raw.removePrefix(BLOB_PREFIX), Base64.NO_WRAP)
            } else {
                raw
            }
            is Number -> raw
            is Boolean -> if (raw) 1L else 0L
            else -> raw.toString()
        }
    }

    /**
     * Produces a deterministic SHA-256 over patient table contents only (excluding
     * the volatile export timestamp and non-patient tables) so unchanged patient
     * databases do not produce duplicate uploads.
     */
    private fun canonicalTablesHash(tables: JSONObject): String {
        val canonical = buildString {
            for (table in PATIENT_TABLES) {
                val array = tables.optJSONArray(table) ?: continue
                append(table).append('=')
                val rowStrings = ArrayList<String>(array.length())
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val sortedCols = obj.keys().asSequence().toList().sorted()
                    rowStrings += sortedCols.joinToString("|") { col ->
                        val v = if (obj.isNull(col)) "NULL" else obj.get(col).toString()
                        "$col:$v"
                    }
                }
                rowStrings.sort()
                rowStrings.forEach { append(it).append('\n') }
            }
        }
        return Hashing.sha256(canonical.toByteArray(Charsets.UTF_8))
    }

    private fun schemaTableNames(): Set<String> {
        val db = database.openHelper.writableDatabase
        val names = mutableSetOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
                    "AND name NOT LIKE 'room_%'"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                names.add(cursor.getString(0))
            }
        }
        return names
    }

    companion object {
        private const val TAG = "DatabaseSnapshot"
        private const val BLOB_PREFIX = "otblob:"
        private const val SCOPE_PATIENTS_ONLY = "patients_only"
        const val FORMAT_VERSION = 1

        /** Strictly ordered parent-before-child allowlist of synced patient tables. */
        val PATIENT_TABLES = listOf(
            "patients",
            "timeline_events",
            "surgeries",
            "patient_images",
            "patient_documents"
        )

        private val MEDIA_PATH_TABLES = setOf("patient_images", "patient_documents")
    }
}

data class SnapshotSummary(
    val tableCount: Int,
    val rowCount: Int,
    val patientCount: Int = 0,
    val patientMediaCount: Int = 0,
    val sizeBytes: Long,
    val createdAt: Long,
    val contentHash: String = ""
)
