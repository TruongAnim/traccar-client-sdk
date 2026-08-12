package org.traccar.client

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Looper
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

@SuppressLint("MissingPermission")
class FusedLocationSource(
    scope: ComponentCoroutineScope,
    context: Context,
    config: Config,
    state: StateFlow<State>,
) : LocationSource {

    private val locationConfig = config.location.effective

    init {
        // `effective` rewrites the request behind the user's back, and the two
        // cases have opposite consequences: HIGHEST asks for everything, while
        // a distance filter asks for nothing until the device moves. Saying
        // which one applies is the difference between a useful log line and a
        // misleading one.
        val requested = config.location
        if (requested.intervalSeconds > 0 && locationConfig.intervalSeconds == 0) {
            if (requested.accuracy == Accuracy.HIGHEST) {
                Log.log(
                    "Accuracy HIGHEST requests fixes as fast as the platform supplies them; " +
                        "the ${requested.intervalSeconds}s interval is applied by the filter instead",
                )
            } else {
                Log.log(
                    "Interval ${requested.intervalSeconds}s not requested from the platform " +
                        "because a ${requested.distanceMeters}m distance filter is set; " +
                        "fixes arrive on movement only",
                )
            }
        }
    }

    private val appContext = context.applicationContext
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)

    override val positions = MutableSharedFlow<Position>(extraBufferCapacity = 8)

    private var callback: LocationCallback? = null
    private var currentLocationToken: CancellationTokenSource? = null

    init {
        scope.observeState(state, State::locationMode, inactive = LocationMode.Off) { mode ->
            try {
                when (mode) {
                    LocationMode.Active -> ensureStarted()
                    LocationMode.Stationary -> ensureStopped(awaitFinalFix = true)
                    LocationMode.Off -> ensureStopped(awaitFinalFix = false)
                }
            } catch (e: SecurityException) {
                Log.log("Location permission missing: $e")
            }
        }
    }

    private fun ensureStarted() {
        if (callback != null) return
        startUpdates(locationConfig)
    }

    private suspend fun ensureStopped(awaitFinalFix: Boolean) {
        if (callback == null) return
        if (awaitFinalFix) {
            val finalFix = awaitCurrentLocation()
            finalFix?.let { positions.emit(it.toPosition()) }
        }
        stopUpdates()
    }

    override suspend fun fetchOnce(): Position? = try {
        (awaitCurrentLocation() ?: awaitLastLocation())?.toPosition()
    } catch (e: SecurityException) {
        Log.log("Location permission missing: $e")
        null
    }

    private fun startUpdates(locationConfig: LocationConfig) {
        val newCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                positions.tryEmit(location.toPosition())
            }
        }
        val request = LocationRequest.Builder(
            locationConfig.accuracy.toFusedPriority(),
            locationConfig.intervalSeconds * 1000L,
        )
            .setMinUpdateDistanceMeters(locationConfig.distanceMeters.toFloat())
            .build()
        client.requestLocationUpdates(request, newCallback, Looper.getMainLooper())
        callback = newCallback
        Log.log(
            "Location updates started (${locationConfig.accuracy}, " +
                "every ${locationConfig.intervalSeconds}s, " +
                "min ${locationConfig.distanceMeters}m)",
        )
    }

    private fun stopUpdates() {
        callback?.let {
            client.removeLocationUpdates(it)
            Log.log("Location updates stopped")
        }
        callback = null
    }

    private suspend fun awaitCurrentLocation(): Location? {
        currentLocationToken?.cancel()
        val token = CancellationTokenSource()
        currentLocationToken = token
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(LOCATION_FETCH_TIMEOUT.inWholeMilliseconds)
            .build()
        return try {
            suspendCancellableCoroutine { continuation ->
                client.getCurrentLocation(request, token.token)
                    .addOnSuccessListener {
                        if (continuation.isActive) continuation.resume(it)
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) continuation.resume(null)
                    }
                continuation.invokeOnCancellation { token.cancel() }
            }
        } finally {
            if (currentLocationToken === token) currentLocationToken = null
        }
    }

    private suspend fun awaitLastLocation(): Location? = suspendCancellableCoroutine { continuation ->
        client.lastLocation
            .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
            .addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
    }

    private fun Location.toPosition(): Position = Position(
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy.toDouble().takeIf { hasAccuracy() && it.isFinite() },
        time = time,
        altitude = altitude.takeIf { hasAltitude() && it.isFinite() },
        speed = speed.toDouble().takeIf { hasSpeed() && it.isFinite() },
        bearing = bearing.toDouble().takeIf { hasBearing() && it.isFinite() },
        extras = telemetry(),
    )

    private fun Location.telemetry(): Map<String, String> = buildMap {
        provider?.takeIf { it.isNotBlank() }?.let { put(Telemetry.PROVIDER, it) }
        // Only the gps provider reports this, and only once it has a fix.
        extras?.getInt("satellites", -1)?.takeIf { it > 0 }
            ?.let { put(Telemetry.SATELLITES, it.toString()) }
        if (isMockFix()) put(Telemetry.MOCK, "true")
    }

    private fun Location.isMockFix(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isMock
        } else {
            @Suppress("DEPRECATION")
            isFromMockProvider
        }

}

private fun Accuracy.toFusedPriority(): Int = when (this) {
    Accuracy.HIGHEST, Accuracy.HIGH -> Priority.PRIORITY_HIGH_ACCURACY
    Accuracy.MEDIUM -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
    Accuracy.LOW -> Priority.PRIORITY_LOW_POWER
}
