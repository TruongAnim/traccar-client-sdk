package org.traccar.client

import app.cash.sqldelight.db.SqlDriver
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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

    suspend fun clear() = withContext(Dispatchers.IO) {
        queries.clear()
    }

    suspend fun trim(level: LogLevel, keep: Int) = withContext(Dispatchers.IO) {
        queries.trimLevel(level = level.key, keep = keep.toLong())
    }
}
