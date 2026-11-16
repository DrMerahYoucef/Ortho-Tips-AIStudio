package com.example.utils

object SearchQuery {
    fun clean(query: String): String {
        return query.trim().replace(Regex("[%_]"), "")
    }

    fun highlightSnippet(text: String, query: String, maxLength: Int = 140): String {
        if (query.isBlank() || !text.contains(query, ignoreCase = true)) {
            return if (text.length > maxLength) text.take(maxLength) + "..." else text
        }
        val index = text.indexOf(query, ignoreCase = true)
        val start = (index - 40).coerceAtLeast(0)
        val end = (index + query.length + 60).coerceAtMost(text.length)
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < text.length) "..." else ""
        return prefix + text.substring(start, end).trim() + suffix
    }
}
