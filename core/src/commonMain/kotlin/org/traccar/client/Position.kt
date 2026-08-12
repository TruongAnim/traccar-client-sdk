package org.traccar.client

import kotlinx.serialization.Serializable

@Serializable
data class Position(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Double? = null,
    val time: Long,
    val altitude: Double? = null,
    val speed: Double? = null,
    val bearing: Double? = null,
    val battery: Int? = null,
    val charging: Boolean? = null,
    val alarm: String? = null,
    /** Device and environment telemetry. See [Telemetry] for the keys. */
    val extras: Map<String, String> = emptyMap(),
)
