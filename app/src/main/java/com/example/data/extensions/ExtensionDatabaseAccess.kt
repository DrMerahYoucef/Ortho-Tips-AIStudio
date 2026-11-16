package com.example.data.extensions

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.data.local.OrthoDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs `OrthoTips.database.query` for extensions holding the `database`
 * permission. Read-only by construction: the SQL comes from
 * [ExtensionDatabaseQuery], which can only produce SELECTs on reference tables.
 */
class ExtensionDatabaseAccess(private val database: OrthoDatabase) {

    /** Blocking; the bridge calls it off the main thread. */
    fun query(spec: JSONObject): JSONArray {
        val sql = ExtensionDatabaseQuery.build(spec)
        val rows = JSONArray()
        database.openHelper.readableDatabase
            .query(SimpleSQLiteQuery(sql.sql, sql.args.toTypedArray()))
            .use { cursor ->
                val columns = cursor.columnNames
                while (cursor.moveToNext()) {
                    val row = JSONObject()
                    columns.forEachIndexed { index, column ->
                        row.put(
                            column,
                            when (cursor.getType(index)) {
                                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                                else -> cursor.getString(index)
                            }
                        )
                    }
                    rows.put(row)
                }
            }
        return rows
    }
}
