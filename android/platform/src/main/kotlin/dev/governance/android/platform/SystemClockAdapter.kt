package dev.governance.android.platform

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Production [Clock] implementation that delegates to [Clock.System].
 *
 * Exists as a named class so production code can depend on [SystemClockAdapter]
 * and tests can inject [dev.governance.testing.MutableTestClock] without the
 * production module depending on `:testing`.
 */
class SystemClockAdapter : Clock {
    override fun now(): Instant = Clock.System.now()
}
