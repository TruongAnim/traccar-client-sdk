package org.traccar.client

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.traccar.client.db.Database

class DatabaseQueue(driver: SqlDriver) : PositionQueue {

    private val queries = Database(driver).positionQueries
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun enqueue(position: Position) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                queries.enqueue(
                    latitude = position.latitude,
                    longitude = position.longitude,
                    accuracy = position.accuracy,
                    time = position.time,
                    altitude = position.altitude,
                    speed = position.speed,
                    bearing = position.bearing,
                    battery = position.battery?.toLong(),
                    charging = position.charging?.let { if (it) 1L else 0L },
                    extras = position.extras
                        .takeIf { it.isNotEmpty() }
                        ?.let { json.encodeToString(it) },
                )
            }
        }
    }

    override suspend fun peek(): Position? = withContext(Dispatchers.IO) {
        mutex.withLock {
            queries.peek().executeAsOneOrNull()?.let { row ->
                Position(
                    latitude = row.latitude,
                    longitude = row.longitude,
                    accuracy = row.accuracy,
                    time = row.time,
                    altitude = row.altitude,
                    speed = row.speed,
                    bearing = row.bearing,
                    battery = row.battery?.toInt(),
                    charging = row.charging?.let { it != 0L },
                    // A row written before this column existed, or holding
                    // something unreadable, must not stall the whole queue.
                    extras = row.extras?.let { raw ->
                        runCatching { json.decodeFromString<Map<String, String>>(raw) }
                            .getOrElse { emptyMap() }
                    } ?: emptyMap(),
                )
            }
        }
    }

    override suspend fun removeFirst() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                queries.removeFirst()
            }
        }
    }
}
