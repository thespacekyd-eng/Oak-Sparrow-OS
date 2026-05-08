package dev.governance.testing

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration

/**
 * A [Clock] whose time can be programmatically advanced, rewound, or set.
 * Used by adversarial tests (e.g., TimeManipulation) to simulate clock
 * anomalies without touching the real system clock.
 */
class MutableTestClock(
    private var current: Instant = Clock.System.now(),
) : Clock {
    override fun now(): Instant = current

    fun advance(duration: Duration) {
        current = current + duration
    }

    fun set(instant: Instant) {
        current = instant
    }
}
