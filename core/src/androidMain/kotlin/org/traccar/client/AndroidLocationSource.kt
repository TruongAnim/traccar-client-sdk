package org.traccar.client

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

@SuppressLint("MissingPermission")
class AndroidLocationSource(
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
    private val locationManager: LocationManager = checkNotNull(appContext.getSystemService())

    override val positions = MutableSharedFlow<Position>(extraBufferCapacity = 8)

    private var listener: LocationListenerCompat? = null
    private var currentLocationCancellation: CancellationSignal? = null

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
        if (listener != null) return
        startUpdates()
    }

    private suspend fun ensureStopped(awaitFinalFix: Boolean) {
        if (listener == null) return
        if (awaitFinalFix) {
            val finalFix = awaitCurrentLocation()
            finalFix?.let { positions.emit(it.toPosition()) }
        }
        stopUpdates()
    }

    override suspend fun fetchOnce(): Position? = try {
        val fresh = withTimeoutOrNull(LOCATION_FETCH_TIMEOUT) { awaitCurrentLocation() }
        (fresh ?: locationManager.getLastKnownLocation(locationConfig.accuracy.toAndroidProvider()))
            ?.toPosition()
    } catch (e: SecurityException) {
        Log.log("Location permission missing: $e")
        null
    }

    private fun startUpdates() {
        val newListener = LocationListenerCompat { location ->
            positions.tryEmit(location.toPosition())
        }
        locationManager.requestLocationUpdates(
            locationConfig.accuracy.toAndroidProvider(),
            locationConfig.intervalSeconds * 1000L,
            locationConfig.distanceMeters.toFloat(),
            newListener,
            Looper.getMainLooper(),
        )
        listener = newListener
        Log.log(
            "Location updates started (${locationConfig.accuracy}, " +
                "every ${locationConfig.intervalSeconds}s, " +
                "min ${locationConfig.distanceMeters}m)",
        )
    }

    private fun stopUpdates() {
        listener?.let {
            locationManager.removeUpdates(it)
            Log.log("Location updates stopped")
        }
        listener = null
    }

    private suspend fun awaitCurrentLocation(): Location? {
        currentLocationCancellation?.cancel()
        val signal = CancellationSignal()
        currentLocationCancellation = signal
        return try {
            suspendCancellableCoroutine { continuation ->
                LocationManagerCompat.getCurrentLocation(
                    locationManager,
                    locationConfig.accuracy.toAndroidProvider(),
                    signal,
                    ContextCompat.getMainExecutor(appContext),
                ) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
                continuation.invokeOnCancellation { signal.cancel() }
            }
        } finally {
            if (currentLocationCancellation === signal) currentLocationCancellation = null
        }
    }

    private fun Location.toPosition(): Position = Position(
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy.toDouble().takeIf { hasAccuracy() && it.isFinite() },
        time = time,
        altitude = altitude.takeIf { hasAltitude() && it.isFinite() },
        speed = speed.toDouble().takeIf { hasSpeed() && it.isFinite() },
        bearing = bearing.toDouble().takeIf { hasBearing() && it.isFinite() },
    )

    private fun Accuracy.toAndroidProvider(): String {
        val preferred = when (this) {
            Accuracy.HIGHEST, Accuracy.HIGH -> LocationManager.GPS_PROVIDER
            Accuracy.MEDIUM -> LocationManager.NETWORK_PROVIDER
            Accuracy.LOW -> LocationManager.PASSIVE_PROVIDER
        }
        val available = locationManager.allProviders
        return preferred.takeIf { it in available } ?: available.firstOrNull()
            ?: throw IllegalStateException("No location provider available")
    }
}
