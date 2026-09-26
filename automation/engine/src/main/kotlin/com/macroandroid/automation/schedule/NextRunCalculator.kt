package com.macroandroid.automation.schedule

import com.macroandroid.automation.model.MissedRunPolicy
import com.macroandroid.automation.model.ScheduleKind
import com.macroandroid.automation.model.ScheduleSpec
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** What to do when a planned occurrence is discovered after it should have fired (doc 08 §4). */
enum class MissedDecision { RUN_NOW, SKIP }

/** Pure schedule arithmetic shared by the planner (automation:android) and the editor (feature:scheduling). */
object NextRunCalculator {

    /** First occurrence strictly after [after], or null when the schedule has no future occurrence. */
    fun next(spec: ScheduleSpec, after: Instant): Instant? {
        val zone = zoneOf(spec)
        return when (val k = spec.kind) {
            is ScheduleKind.OneTime -> k.at.takeIf { it > after }
            is ScheduleKind.Interval -> after + k.minutes.minutes
            is ScheduleKind.Daily -> nextDaily(k, after, zone)
        }
    }

    /** Next occurrence relative to the previous *planned* time (keeps interval schedules from drifting). */
    fun nextAfterPlanned(spec: ScheduleSpec, planned: Instant, now: Instant): Instant? = when (val k = spec.kind) {
        is ScheduleKind.Interval -> {
            val step = k.minutes.minutes
            var candidate = planned + step
            // Skip the occurrences the device slept through; they are governed by the missed-run policy, not replayed.
            if (candidate <= now) {
                val missed = ((now - candidate) / step).toInt() + 1
                candidate += step * missed
            }
            candidate
        }
        else -> next(spec, maxOf(planned, now))
    }

    /** Lateness of an occurrence planned at [planned] when observed at [now]. */
    fun lateness(planned: Instant, now: Instant): Duration = (now - planned).coerceAtLeast(Duration.ZERO)

    fun isLate(spec: ScheduleSpec, planned: Instant, now: Instant): Boolean = lateness(planned, now) > spec.lateThreshold

    /** Decision for a late occurrence; [alreadyRanSincePlanned] implements RUN_ONCE_COALESCED. */
    fun decideMissed(spec: ScheduleSpec, alreadyRanSincePlanned: Boolean): MissedDecision = when (spec.missedRunPolicy) {
        MissedRunPolicy.RUN_LATE -> MissedDecision.RUN_NOW
        MissedRunPolicy.SKIP -> MissedDecision.SKIP
        MissedRunPolicy.RUN_ONCE_COALESCED -> if (alreadyRanSincePlanned) MissedDecision.SKIP else MissedDecision.RUN_NOW
    }

    fun zoneOf(spec: ScheduleSpec): TimeZone = runCatching { TimeZone.of(spec.zoneId) }.getOrDefault(TimeZone.UTC)

    private fun nextDaily(k: ScheduleKind.Daily, after: Instant, zone: TimeZone): Instant? {
        if (k.daysOfWeek.isEmpty()) return null
        val local: LocalDateTime = after.toLocalDateTime(zone)
        var date = local.date
        repeat(DAYS_TO_SCAN) {
            if (date.dayOfWeek in k.daysOfWeek) {
                // toInstant resolves DST gaps/overlaps deterministically (kotlinx-datetime picks the later offset on gaps).
                val candidate = date.atTime(k.localTime).toInstant(zone)
                if (candidate > after) return candidate
            }
            date = date.plus(1, DateTimeUnit.DAY)
        }
        return null
    }

    /** One week covers every weekday; a second week guards against a DST gap swallowing the only slot. */
    private const val DAYS_TO_SCAN = 15
}
