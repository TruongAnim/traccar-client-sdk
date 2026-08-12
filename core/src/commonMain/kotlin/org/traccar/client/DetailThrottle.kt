package org.traccar.client

/**
 * Rate limits recurring detail entries, one budget per kind.
 *
 * Keyed rather than global so a message that repeats every second cannot
 * suppress an unrelated one that lands in the same window, and counting the
 * suppressed entries keeps "this is still happening" visible without a line
 * per occurrence.
 *
 * Takes the clock as an argument so the decision is a pure function of the
 * timestamps it is given.
 */
internal class DetailThrottle {

    private class Entry(var lastAt: Long = Long.MIN_VALUE, var suppressed: Int = 0)

    private val entries = mutableMapOf<String, Entry>()

    /**
     * Returns the suffix to append when this entry should be recorded, or null
     * when it falls inside the quiet window and should be dropped.
     */
    fun claim(key: String, nowMillis: Long, intervalMillis: Long): String? {
        if (intervalMillis <= 0L) return ""
        val entry = entries.getOrPut(key) { Entry() }
        // Long.MIN_VALUE would overflow the subtraction, so the first call is
        // handled by its own branch rather than by arithmetic.
        val due = entry.lastAt == Long.MIN_VALUE || nowMillis - entry.lastAt >= intervalMillis
        if (!due) {
            entry.suppressed++
            return null
        }
        val suppressed = entry.suppressed
        entry.lastAt = nowMillis
        entry.suppressed = 0
        return if (suppressed > 0) " (+$suppressed more)" else ""
    }

    fun reset() = entries.clear()
}
