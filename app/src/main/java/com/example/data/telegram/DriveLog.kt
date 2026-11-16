package com.example.data.telegram

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Logging facade for the Telegram Drive backend, fulfilling the role TeleDrive's
 * `SafeLog` plays there and additionally retaining the last [CAPACITY] entries in
 * memory so the in-app diagnostics screen can show them without a USB cable.
 *
 * Messages must never contain phone numbers, API credentials, patient names or
 * file contents. Everything logged here is structural: state transitions, counts,
 * sizes, opaque identifiers.
 */
object DriveLog {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String
    ) {
        fun format(): String =
            "${TIME_FORMAT.format(Date(timestamp))} ${level.name.first()}/$tag: $message"
    }

    private const val CAPACITY = 500
    private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    var verbose: Boolean = BuildConfig.DEBUG

    private val buffer = ArrayDeque<Entry>(CAPACITY)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Newest entry last. Backs the diagnostics screen. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun d(tag: String, message: String) {
        if (verbose) Log.d(tag, message)
        record(Level.DEBUG, tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        record(Level.INFO, tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        record(Level.WARN, tag, message + throwable.suffix())
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        record(Level.ERROR, tag, message + throwable.suffix())
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
    }

    /** Whole buffer as text, for the "share logs" action in diagnostics. */
    @Synchronized
    fun dump(): String = buffer.joinToString("\n") { it.format() }

    @Synchronized
    private fun record(level: Level, tag: String, message: String) {
        if (buffer.size >= CAPACITY) buffer.pollFirst()
        buffer.addLast(Entry(System.currentTimeMillis(), level, tag, message))
        _entries.value = buffer.toList()
    }

    private fun Throwable?.suffix(): String =
        this?.let { " (${it::class.java.simpleName}: ${it.message})" } ?: ""
}
