package com.macroandroid.automation.schedule

import com.google.common.truth.Truth.assertThat
import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.model.MissedRunPolicy
import com.macroandroid.automation.model.ScheduleId
import com.macroandroid.automation.model.ScheduleKind
import com.macroandroid.automation.model.ScheduleSpec
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class NextRunCalculatorTest {
    private fun spec(kind: ScheduleKind, zone: String = "Europe/Berlin", policy: MissedRunPolicy = MissedRunPolicy.RUN_LATE) =
        ScheduleSpec(ScheduleId("s"), MacroId("m"), kind = kind, zoneId = zone, missedRunPolicy = policy)

    @Test
    fun `one time only fires once in the future`() {
        val at = Instant.parse("2026-10-01T10:00:00Z")
        val s = spec(ScheduleKind.OneTime(at))
        assertThat(NextRunCalculator.next(s, at - 1.minutes)).isEqualTo(at)
        assertThat(NextRunCalculator.next(s, at)).isNull()
    }

    @Test
    fun `interval keeps cadence from planned time and skips slept-through slots`() {
        val s = spec(ScheduleKind.Interval(30))
        val planned = Instant.parse("2026-10-01T10:00:00Z")
        assertThat(NextRunCalculator.nextAfterPlanned(s, planned, planned + 5.minutes)).isEqualTo(planned + 30.minutes)
        // Device slept 2h10m: the next slot is the first future one on the same grid.
        assertThat(NextRunCalculator.nextAfterPlanned(s, planned, planned + 130.minutes)).isEqualTo(planned + 150.minutes)
    }

    @Test
    fun `daily picks the next selected weekday at local time`() {
        // 2026-10-01 is a Thursday.
        val s = spec(ScheduleKind.Daily(LocalTime(7, 30), setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)))
        val after = Instant.parse("2026-10-01T12:00:00Z")
        val next = NextRunCalculator.next(s, after)!!
        val local = next.toLocalDateTime(TimeZone.of("Europe/Berlin"))
        assertThat(local.dayOfWeek).isEqualTo(DayOfWeek.FRIDAY)
        assertThat(local.time).isEqualTo(LocalTime(7, 30))
        assertThat(local.date.day).isEqualTo(2)
    }

    @Test
    fun `daily across DST change keeps local wall time`() {
        // Europe/Berlin leaves DST on 2026-10-25.
        val s = spec(ScheduleKind.Daily(LocalTime(8, 0), DayOfWeek.entries.toSet()))
        val before = Instant.parse("2026-10-24T09:00:00Z")
        val first = NextRunCalculator.next(s, before)!!
        val second = NextRunCalculator.next(s, first)!!
        assertThat(second - first).isEqualTo(25.hours)
        assertThat(second.toLocalDateTime(TimeZone.of("Europe/Berlin")).time).isEqualTo(LocalTime(8, 0))
    }

    @Test
    fun `missed policy decisions`() {
        val late = spec(ScheduleKind.Interval(60), policy = MissedRunPolicy.RUN_ONCE_COALESCED)
        assertThat(NextRunCalculator.decideMissed(late, alreadyRanSincePlanned = false)).isEqualTo(MissedDecision.RUN_NOW)
        assertThat(NextRunCalculator.decideMissed(late, alreadyRanSincePlanned = true)).isEqualTo(MissedDecision.SKIP)
        assertThat(NextRunCalculator.decideMissed(spec(ScheduleKind.Interval(60), policy = MissedRunPolicy.SKIP), false))
            .isEqualTo(MissedDecision.SKIP)
        val s = spec(ScheduleKind.Interval(60))
        val planned = Instant.parse("2026-10-01T10:00:00Z")
        assertThat(NextRunCalculator.isLate(s, planned, planned + 10.minutes)).isFalse()
        assertThat(NextRunCalculator.isLate(s, planned, planned + 31.minutes)).isTrue()
    }

    @Test
    fun `invalid zone falls back to UTC`() {
        assertThat(NextRunCalculator.zoneOf(spec(ScheduleKind.Interval(15), zone = "Nowhere/Land"))).isEqualTo(TimeZone.UTC)
    }
}
