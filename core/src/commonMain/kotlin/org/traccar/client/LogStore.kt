package org.traccar.client

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.traccar.client.db.Database

class LogStore(driver: SqlDriver) {

    private val queries = Database(driver).logEntryQueries

    suspend fun insert(message: String, level: LogLevel = LogLevel.INFO) =
        withContext(Dispatchers.IO) {
            queries.insert(
                time = Clock.System.now().toEpochMilliseconds(),
                message = message,
                level = level.key,
            )
        }

    suspend fun all(): List<LogEntry> = withContext(Dispatchers.IO) {
        queries.selectAll().executeAsList().map {
            LogEntry(time = it.time, message = it.message, level = LogLevel.fromKey(it.level))
        }
    }

    /**
     * Emits again whenever an entry is written through this driver, so a UI
     * collecting it needs no polling.
     *
     * Bounded on purpose: the query re-runs on every insert, and detail
     * logging can insert once a second. Reading the whole table each time
     * would make watching the log more expensive than producing it.
     */
    fun observeRecent(limit: Int = 1000): Flow<List<LogEntry>> =
        queries.selectRecent(limit.toLong())
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows ->
                rows.map {
                    LogEntry(
                        time = it.time,
                        message = it.message,
                        level = LogLevel.fromKey(it.level),
                    )
                }
            }

    suspend fun clear() = withContext(Dispatchers.IO) {
        queries.clear()
    }

    suspend fun trim(level: LogLevel, keep: Int) = withContext(Dispatchers.IO) {
        queries.trimLevel(level = level.key, keep = keep.toLong())
    }
}
