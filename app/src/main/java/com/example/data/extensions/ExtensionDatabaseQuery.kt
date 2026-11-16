package com.example.data.extensions

import org.json.JSONObject

/**
 * Turns an `OrthoTips.database.query(spec)` call into safe, read-only SQL.
 *
 * The contract the guide leaves to the implementation (section 5):
 *
 * ```js
 * await OrthoTips.database.query({
 *   table: 'bones',              // chapters | bones | sections | images
 *   where: { chapterId: 'chap_shoulder' },   // equality on listed columns
 *   search: 'clavicle',          // optional LIKE over the table's text columns
 *   orderBy: 'orderIndex', descending: false,
 *   limit: 100, offset: 0        // limit ≤ 500
 * })  // → array of row objects
 * ```
 *
 * Only the clinical reference tables are reachable, column by column. Patient
 * records, notes and sync state are not in the list, so no spec can name them;
 * every value travels as a bound argument, never inside the SQL text.
 */
object ExtensionDatabaseQuery {

    data class Sql(val sql: String, val args: List<Any>)

    private class Table(val columns: List<String>, val searchable: List<String>)

    private val TABLES = mapOf(
        "chapters" to Table(
            columns = listOf("id", "title", "description", "titleFr", "descriptionFr", "icon", "orderIndex"),
            searchable = listOf("title", "description", "titleFr", "descriptionFr")
        ),
        "bones" to Table(
            columns = listOf(
                "id", "chapterId", "name", "subtitle", "region", "traumaType", "classificationSummary",
                "nameFr", "subtitleFr", "regionFr", "classificationSummaryFr", "orderIndex", "isHighYield"
            ),
            searchable = listOf("name", "subtitle", "region", "classificationSummary", "nameFr", "subtitleFr", "regionFr")
        ),
        "sections" to Table(
            columns = listOf(
                "id", "boneId", "sectionType", "title", "content", "pearls", "warnings",
                "titleFr", "contentFr", "pearlsFr", "warningsFr", "orderIndex"
            ),
            searchable = listOf("title", "content", "pearls", "warnings", "titleFr", "contentFr")
        ),
        // Local file paths (imagePath, drawableResName) are deliberately absent.
        "images" to Table(
            columns = listOf("id", "boneId", "sectionId", "title", "description", "titleFr", "descriptionFr", "figureNumber", "tags"),
            searchable = listOf("title", "description", "titleFr", "descriptionFr", "tags")
        )
    )

    const val DEFAULT_LIMIT = 100
    const val MAX_LIMIT = 500

    val tables: Set<String> get() = TABLES.keys

    fun build(spec: JSONObject): Sql {
        val tableName = spec.optString("table")
        val table = TABLES[tableName] ?: throw IllegalArgumentException(
            "Unknown table '$tableName'. Available: ${TABLES.keys.joinToString()}"
        )
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()

        spec.optJSONObject("where")?.let { where ->
            for (column in where.keys()) {
                if (column !in table.columns) throw IllegalArgumentException("Unknown column '$column' in $tableName")
                when (val value = where.get(column)) {
                    JSONObject.NULL -> clauses += "`$column` IS NULL"
                    is Boolean -> { clauses += "`$column` = ?"; args += if (value) 1 else 0 }
                    is Number, is String -> { clauses += "`$column` = ?"; args += value }
                    else -> throw IllegalArgumentException("Unsupported value for '$column'")
                }
            }
        }

        spec.optString("search").trim().takeIf { it.isNotEmpty() }?.let { text ->
            val pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            clauses += table.searchable.joinToString(" OR ", "(", ")") { "IFNULL(`$it`, '') LIKE ? ESCAPE '\\'" }
            repeat(table.searchable.size) { args += pattern }
        }

        val orderBy = spec.optString("orderBy").takeIf { it.isNotEmpty() }
            ?: if ("orderIndex" in table.columns) "orderIndex" else "id"
        if (orderBy !in table.columns) throw IllegalArgumentException("Unknown column '$orderBy' in $tableName")
        val direction = if (spec.optBoolean("descending", false)) "DESC" else "ASC"
        val limit = spec.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val offset = spec.optInt("offset", 0).coerceAtLeast(0)

        val sql = buildString {
            append("SELECT ").append(table.columns.joinToString(", ") { "`$it`" })
            append(" FROM `").append(tableName).append('`')
            if (clauses.isNotEmpty()) append(" WHERE ").append(clauses.joinToString(" AND "))
            append(" ORDER BY `").append(orderBy).append("` ").append(direction)
            append(" LIMIT ").append(limit).append(" OFFSET ").append(offset)
        }
        return Sql(sql, args)
    }
}
