package io.github.iampopovich.eculense.core.model

/**
 * Injectable logger for pure-JVM and Android modules (the Android implementation lives in :app and writes to
 * logcat). Messages must never contain a VIN, GPS coordinates, API keys or other personal data: log ids, counts
 * and exception classes instead.
 */
interface AppLogger {
    fun warn(tag: String, message: String, error: Throwable? = null)

    fun error(tag: String, message: String, error: Throwable? = null)

    companion object {
        /** Fallback for code paths and tests without the app's implementation. */
        val NONE: AppLogger = object : AppLogger {
            override fun warn(tag: String, message: String, error: Throwable?) = Unit
            override fun error(tag: String, message: String, error: Throwable?) = Unit
        }
    }
}

/**
 * [runCatching] that writes a failure to the log as a warning ([what] = short English description without personal
 * data). Cancellation is rethrown, not swallowed.
 */
inline fun <T> AppLogger.attempt(tag: String, what: String, block: () -> T): Result<T> =
    runCatching(block).onFailure { e ->
        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
        warn(tag, "$what failed: ${e::class.java.simpleName}", e)
    }

/** Keeps every entry in memory; for tests. */
class RecordingLogger : AppLogger {
    data class Entry(val level: Char, val tag: String, val message: String, val error: Throwable?)

    private val _entries = mutableListOf<Entry>()
    val entries: List<Entry> get() = synchronized(_entries) { _entries.toList() }

    override fun warn(tag: String, message: String, error: Throwable?) {
        synchronized(_entries) { _entries += Entry('W', tag, message, error) }
    }

    override fun error(tag: String, message: String, error: Throwable?) {
        synchronized(_entries) { _entries += Entry('E', tag, message, error) }
    }
}
