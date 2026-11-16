package com.example.utils

object Slug {
    fun generate(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')
    }
}
