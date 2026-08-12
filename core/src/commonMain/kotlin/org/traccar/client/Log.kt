package org.traccar.client

import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object Log {

    @Volatile
    var store: LogStore? = null

    @Volatile
    var retention: Int = 5000

    /**
     * Detail entries are kept separately and in smaller numbers: they arrive
     * far more often, and trimming them against the same budget would push the
     * main log out within minutes of tracking.
     */
    @Volatile
    var detailRetention: Int = 2000

    /**
     * Minimum gap between two throttled detail entries of the same kind.
     * Zero records every one of them.
     */
    @Volatile
    var detailIntervalMillis: Long = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val throttle = DetailThrottle()
    private val mutex = Mutex()

    /** The main narrative: what the tracker did. */
    fun log(message: String) = write(message, LogLevel.INFO)

    /** Why it did it, including why it did nothing. */
    fun detail(message: String) = write(message, LogLevel.DETAIL)

    /**
     * A detail entry that recurs as fast as fixes arrive.
     *
     * Rate limited per [key] rather than globally, so a message that repeats
     * every second cannot hide an unrelated one that happens to fall in the
     * same window. What was left out is not lost: the next entry through says
     * how many of its own kind it stands for, which keeps "this is still
     * happening every second" visible without a line per second.
     */
    fun detail(key: String, message: String) {
        scope.launch {
            val target = store ?: return@launch
            val suffix = claim(key) ?: return@launch
            target.insert(message + suffix, LogLevel.DETAIL)
            target.trim(LogLevel.DETAIL, detailRetention)
        }
    }

    /** Null when this entry is being suppressed; otherwise the count suffix. */
    private suspend fun claim(key: String): String? {
        val now = Clock.System.now().toEpochMilliseconds()
        return mutex.withLock { throttle.claim(key, now, detailIntervalMillis) }
    }

    private fun write(message: String, level: LogLevel) {
        scope.launch {
            val target = store ?: return@launch
            target.insert(message, level)
            target.trim(level, if (level == LogLevel.DETAIL) detailRetention else retention)
        }
    }
}
