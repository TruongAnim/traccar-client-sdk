package org.traccar.client

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.telephony.TelephonyManager
import androidx.core.content.getSystemService

/**
 * Fills in everything about the device that a position should carry.
 *
 * Every reading is taken at the moment a position is processed rather than
 * from a subscription, so nothing here costs battery between fixes. This runs
 * for accepted positions and for the immediate request behind SOS, which is
 * why it stays a single processor rather than one per source of data.
 */
class AndroidDeviceProcessor(
    context: Context,
    private val activityState: ActivityState,
) : PositionProcessor {

    private val appContext = context.applicationContext
    private val batteryManager: BatteryManager? = appContext.getSystemService()
    private val connectivityManager: ConnectivityManager? = appContext.getSystemService()
    private val powerManager: PowerManager? = appContext.getSystemService()
    private val telephonyManager: TelephonyManager? = appContext.getSystemService()

    override suspend fun process(position: Position): Position = position.copy(
        battery = position.battery ?: batteryLevel(),
        charging = position.charging ?: batteryManager?.isCharging,
        // The position already carries what the fix itself knew - provider,
        // satellites, mock - and those win over anything named the same here.
        extras = collect() + position.extras,
    )

    private fun collect(): Map<String, String> = buildMap {
        activityState.activity?.let { put(Telemetry.ACTIVITY, it) }
        activityState.confidence?.let { put(Telemetry.ACTIVITY_CONFIDENCE, it.toString()) }
        put(Telemetry.NETWORK, networkType())
        carrier()?.let { put(Telemetry.CARRIER, it) }
        screenState()?.let { put(Telemetry.SCREEN, it) }
        batteryTemperature()?.let { put(Telemetry.BATTERY_TEMPERATURE, it) }
    }

    private fun networkType(): String {
        val capabilities = connectivityManager?.let {
            it.getNetworkCapabilities(it.activeNetwork)
        } ?: return "none"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }

    private fun carrier(): String? =
        telephonyManager?.networkOperatorName?.takeIf { it.isNotBlank() }

    private fun screenState(): String? =
        powerManager?.let { if (it.isInteractive) "on" else "off" }

    /** Reported in tenths of a degree; returned here in whole degrees. */
    private fun batteryTemperature(): String? {
        val sticky = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return null
        val tenths = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenths == Int.MIN_VALUE) return null
        val celsius = tenths / 10.0
        // A missing sensor reports 0; anything outside what a battery survives
        // is a bad reading rather than a cold phone.
        return if (celsius > -30.0 && celsius < 100.0) celsius.toString() else null
    }

    private fun batteryLevel(): Int? {
        val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?: return null
        return if (level in 0..100) level else null
    }
}
