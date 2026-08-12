package org.traccar.client

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The main narrative: what the tracker did. */
    fun log(message: String) = write(message, LogLevel.INFO)

    /** Why it did it, including why it did nothing. */
    fun detail(message: String) = write(message, LogLevel.DETAIL)

    private fun write(message: String, level: LogLevel) {
        scope.launch {
            val target = store ?: return@launch
            target.insert(message, level)
            target.trim(level, if (level == LogLevel.DETAIL) detailRetention else retention)
        }
    }
}
