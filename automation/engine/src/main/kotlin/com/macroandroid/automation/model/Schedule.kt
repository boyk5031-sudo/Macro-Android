package com.macroandroid.automation.model

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@Serializable
enum class MissedRunPolicy { RUN_LATE, SKIP, RUN_ONCE_COALESCED }

@Serializable
sealed interface ScheduleKind {
    @Serializable
    @SerialName("oneTime")
    data class OneTime(val at: Instant) : ScheduleKind

    /** Every [minutes] minutes; WorkManager minimum is 15. */
    @Serializable
    @SerialName("interval")
    data class Interval(val minutes: Int) : ScheduleKind

    @Serializable
    @SerialName("daily")
    data class Daily(val localTime: LocalTime, val daysOfWeek: Set<DayOfWeek>) : ScheduleKind
}

@Serializable
data class ScheduleSpec(
    val id: ScheduleId,
    val macroId: MacroId,
    val enabled: Boolean = true,
    /** IANA zone id used for [ScheduleKind.Daily]; defaults to the device zone at creation. */
    val zoneId: String = "UTC",
    val kind: ScheduleKind,
    val requiresCharging: Boolean = false,
    val requiresBatteryNotLow: Boolean = true,
    val requiresDeviceIdle: Boolean = false,
    val missedRunPolicy: MissedRunPolicy = MissedRunPolicy.RUN_LATE,
    val lateThreshold: Duration = 30.minutes,
)
