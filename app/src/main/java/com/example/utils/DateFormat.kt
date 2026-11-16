package com.example.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateFormat {
    private val displayFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val displayFormatWithTime = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    fun formatDate(epochMillis: Long?): String {
        if (epochMillis == null) return "—"
        return displayFormat.format(Date(epochMillis))
    }

    fun formatDateTime(epochMillis: Long?): String {
        if (epochMillis == null) return "—"
        return displayFormatWithTime.format(Date(epochMillis))
    }
}
