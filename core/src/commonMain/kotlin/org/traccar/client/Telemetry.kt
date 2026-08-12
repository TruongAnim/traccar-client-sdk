package org.traccar.client

/**
 * Keys for [Position.extras].
 *
 * Telemetry that describes the device or its surroundings rather than the
 * position itself. It lives in a map instead of as fields on [Position]
 * because what is available differs by platform - satellite counts and mock
 * flags exist on Android and not on iOS - and a shared model should not carry
 * columns that half the targets can never fill.
 *
 * The names double as the form parameters sent to the server.
 */
object Telemetry {
    /** still, walking, running, on_bicycle, in_vehicle. */
    const val ACTIVITY = "activity"

    /** Percent confidence the platform reported for [ACTIVITY]. */
    const val ACTIVITY_CONFIDENCE = "activity_confidence"

    /** wifi, cellular, ethernet, other, none. */
    const val NETWORK = "network"

    /** Mobile network operator name, when there is one. */
    const val CARRIER = "carrier"

    /** on or off - whether the screen was awake when the fix was recorded. */
    const val SCREEN = "screen"

    /** Which location provider produced the fix: gps, network, fused. */
    const val PROVIDER = "provider"

    /** Satellites used in the fix. Reported by the gps provider only. */
    const val SATELLITES = "satellites"

    /** Present and true when the fix came from a mock provider. */
    const val MOCK = "mock"

    /** Battery temperature in degrees Celsius. */
    const val BATTERY_TEMPERATURE = "battery_temperature"

    /**
     * Names the uploader already sends as first-class parameters. Telemetry
     * must never shadow them, or a stray key could rewrite the position.
     */
    internal val RESERVED = setOf(
        "id", "lat", "lon", "timestamp", "accuracy",
        "altitude", "speed", "bearing", "batt", "charge", "alarm",
    )
}
