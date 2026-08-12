package org.traccar.client

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class LocationFilter(
    config: Config,
    private val stateStore: StateStore,
) : PositionProcessor {

    private val locationConfig: LocationConfig = config.location
    private var lastAccepted: Position? = stateStore.state.value.lastAcceptedLocation
    private var lastProcessedPaused: Boolean = stateStore.state.value.paused

    override suspend fun process(position: Position): Position? {
        if (position.latitude == null || position.longitude == null) {
            Log.log("Heartbeat accepted")
            return position
        }
        val currentPaused = stateStore.state.value.paused
        if (currentPaused != lastProcessedPaused) {
            lastProcessedPaused = currentPaused
            persistAccepted(position)
            Log.log("Transition accepted ${position.latitude},${position.longitude}")
            return position
        }
        val previous = lastAccepted
        if (previous == null) {
            persistAccepted(position)
            Log.log("Location accepted ${position.latitude},${position.longitude} (first fix)")
            return position
        }

        val elapsedMillis = position.time - previous.time
        val movedMeters = distance(previous, position)
        val turnedDegrees =
            if (previous.bearing != null && position.bearing != null) {
                bearingChange(previous.bearing, position.bearing)
            } else {
                null
            }

        val timeTrigger = locationConfig.intervalSeconds > 0 &&
            elapsedMillis >= locationConfig.intervalSeconds * 1000L
        // Zero means "do not filter on distance", the same way zero means it
        // for interval and angle. Treating it as a >= 0 comparison instead
        // made the trigger always fire, which silently disabled the interval
        // and reported every fix the platform produced.
        val distanceTrigger = locationConfig.distanceMeters > 0 &&
            movedMeters >= locationConfig.distanceMeters
        val angleTrigger = locationConfig.angleDegrees > 0 &&
            turnedDegrees != null && turnedDegrees >= locationConfig.angleDegrees
        // With nothing configured there is no criterion to fail.
        val unfiltered = locationConfig.distanceMeters == 0 &&
            locationConfig.intervalSeconds == 0 &&
            locationConfig.angleDegrees == 0

        if (unfiltered || timeTrigger || distanceTrigger || angleTrigger) {
            persistAccepted(position)
            val reason = buildList {
                if (distanceTrigger) add("moved ${movedMeters.roundToInt()} m")
                if (timeTrigger) add("elapsed ${elapsedMillis / 1000} s")
                if (angleTrigger) add("turned ${turnedDegrees!!.roundToInt()}°")
                if (isEmpty()) add("no filter configured")
            }.joinToString(" + ")
            Log.log("Location accepted ${position.latitude},${position.longitude} ($reason)")
            return position
        }

        // The one entry that explains an otherwise silent gap in the track:
        // a fix did arrive, and here is exactly how far short it fell.
        Log.detail(
            "Location skipped ${position.latitude},${position.longitude}: " +
                skipReason(movedMeters, elapsedMillis, turnedDegrees),
        )
        return null
    }

    private fun skipReason(
        movedMeters: Double,
        elapsedMillis: Long,
        turnedDegrees: Double?,
    ): String = buildList {
        if (locationConfig.distanceMeters > 0) {
            add("moved ${movedMeters.roundToInt()} m of ${locationConfig.distanceMeters} m")
        }
        if (locationConfig.intervalSeconds > 0) {
            add("elapsed ${elapsedMillis / 1000} s of ${locationConfig.intervalSeconds} s")
        }
        if (locationConfig.angleDegrees > 0) {
            add(
                if (turnedDegrees == null) {
                    "no bearing reported"
                } else {
                    "turned ${turnedDegrees.roundToInt()}° of ${locationConfig.angleDegrees}°"
                },
            )
        }
    }.joinToString(", ")

    private suspend fun persistAccepted(position: Position) {
        lastAccepted = position
        stateStore.update { it.copy(lastAcceptedLocation = position) }
    }

    private fun distance(from: Position, to: Position): Double {
        val fromLatitudeRadians = from.latitude!! * PI / 180
        val toLatitudeRadians = to.latitude!! * PI / 180
        val latitudeDelta = (to.latitude - from.latitude) * PI / 180
        val longitudeDelta = (to.longitude!! - from.longitude!!) * PI / 180
        val haversine = sin(latitudeDelta / 2).pow(2) +
            cos(fromLatitudeRadians) * cos(toLatitudeRadians) * sin(longitudeDelta / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(haversine))
    }

    private fun bearingChange(from: Double, to: Double): Double {
        val diff = abs(from - to)
        return if (diff > 180) 360 - diff else diff
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
