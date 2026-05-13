package dev.governance.android.app.ui

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Formats a past timestamp relative to a reference instant, for
 * display in activity feeds.
 *
 * Rules, in order:
 * - <60s ago        -> "just now"
 * - <60min ago      -> "Nm ago"
 * - same day        -> "HH:MM"  (24h, zero-padded)
 * - yesterday       -> "yesterday HH:MM"
 * - <7 days ago     -> "{Weekday} HH:MM"      (e.g. "Mon 14:08")
 * - else            -> "MMM d HH:MM"          (e.g. "May 3 14:08")
 *
 * Pure function: takes [now] explicitly so tests are deterministic.
 */
object RelativeTime {
    fun format(
        timestamp: Instant,
        now: Instant = Clock.System.now(),
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val delta = now - timestamp
        if (delta < 60.seconds) return "just now"
        if (delta < 60.minutes) return "${delta.inWholeMinutes}m ago"

        val ldt = timestamp.toLocalDateTime(zone)
        val nowLdt = now.toLocalDateTime(zone)
        val dayDelta = nowLdt.date.toEpochDays() - ldt.date.toEpochDays()

        val hhmm = "%02d:%02d".format(ldt.hour, ldt.minute)

        return when {
            dayDelta == 0 -> hhmm
            dayDelta == 1 -> "yesterday $hhmm"
            dayDelta in 2..6 -> {
                val dow = ldt.dayOfWeek.name.lowercase()
                    .replaceFirstChar { it.titlecase() }.take(3)
                "$dow $hhmm"
            }
            else -> {
                val mon = ldt.month.name.lowercase()
                    .replaceFirstChar { it.titlecase() }.take(3)
                "$mon ${ldt.dayOfMonth} $hhmm"
            }
        }
    }
}
