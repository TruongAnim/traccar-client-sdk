package org.traccar.client

/**
 * How much detail an entry carries.
 *
 * [INFO] is the narrative of what the tracker did: started, accepted a
 * position, uploaded it. [DETAIL] explains the decisions behind that
 * narrative, including the ones that led to nothing happening - a fix that was
 * filtered out, a signal that arrived in the wrong state. Those are the
 * entries that answer "why did it not send anything?", and they are far too
 * frequent to belong in the main log.
 */
enum class LogLevel {
    INFO,
    DETAIL,
    ;

    internal val key: String get() = name.lowercase()

    internal companion object {
        fun fromKey(key: String?): LogLevel =
            if (key == DETAIL.key) DETAIL else INFO
    }
}

data class LogEntry(
    val time: Long,
    val message: String,
    val level: LogLevel = LogLevel.INFO,
)
