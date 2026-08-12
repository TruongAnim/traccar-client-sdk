package org.traccar.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DetailThrottleTest {

    private val interval = 5_000L

    @Test
    fun recordsTheFirstEntryImmediately() {
        val throttle = DetailThrottle()
        assertEquals("", throttle.claim("skip", nowMillis = 1_000, intervalMillis = interval))
    }

    @Test
    fun dropsEntriesInsideTheQuietWindow() {
        val throttle = DetailThrottle()
        throttle.claim("skip", 0, interval)
        assertNull(throttle.claim("skip", 1_000, interval))
        assertNull(throttle.claim("skip", 4_999, interval))
    }

    @Test
    fun recordsAgainOnceTheWindowHasPassedAndReportsWhatWasDropped() {
        val throttle = DetailThrottle()
        throttle.claim("skip", 0, interval)
        repeat(4) { throttle.claim("skip", 1_000L * (it + 1), interval) }

        assertEquals(" (+4 more)", throttle.claim("skip", 5_000, interval))
        // The counter resets, so the next window reports only its own drops.
        assertEquals("", throttle.claim("skip", 10_000, interval))
    }

    @Test
    fun keepsSeparateBudgetsPerKey() {
        val throttle = DetailThrottle()
        throttle.claim("skip", 0, interval)
        // A different kind in the same window must not be suppressed by it.
        assertEquals("", throttle.claim("activity", 1_000, interval))
        assertNull(throttle.claim("skip", 1_000, interval))
    }

    @Test
    fun recordsEverythingWhenTheIntervalIsZero() {
        val throttle = DetailThrottle()
        assertEquals("", throttle.claim("skip", 0, intervalMillis = 0))
        assertEquals("", throttle.claim("skip", 1, intervalMillis = 0))
        assertEquals("", throttle.claim("skip", 2, intervalMillis = 0))
    }

    @Test
    fun treatsTheBoundaryAsDue() {
        val throttle = DetailThrottle()
        throttle.claim("skip", 0, interval)
        assertEquals("", throttle.claim("skip", interval, interval))
    }

    @Test
    fun survivesTimestampsNearTheLongBoundary() {
        val throttle = DetailThrottle()
        // A first call must not compute now - Long.MIN_VALUE and overflow.
        assertEquals("", throttle.claim("skip", Long.MAX_VALUE - 1, interval))
    }
}
