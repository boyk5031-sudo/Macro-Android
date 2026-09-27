package com.macroandroid.automation.trigger

import com.macroandroid.core.common.display.DisplayOrientation
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/*
 * Trigger Area model (docs/phase-12-trigger-areas.md §3).
 *
 * Coordinates are stored NORMALISED: fractions (0.0..1.0) of the full physical display width/height in the
 * orientation the configuration was authored in. They are converted to physical pixels at activation time by
 * [CoordinateConverter] against the *current* [com.macroandroid.core.common.display.DisplayGeometry], so a
 * configuration survives density changes and resolution changes with the same aspect ratio. Orientation is
 * part of the configuration: a portrait trigger is suspended while the device is in landscape (§4.3).
 */

@Serializable
@JvmInline
value class TriggerId(val value: String) {
    override fun toString(): String = value

    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun random(): TriggerId = TriggerId(Uuid.random().toString())
    }
}

@Serializable
@JvmInline
value class TargetPointId(val value: String) {
    override fun toString(): String = value

    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun random(): TargetPointId = TargetPointId(Uuid.random().toString())
    }
}

/** Normalised rectangle (fractions of display width/height); the activation zone. */
@Serializable
data class TriggerArea(val x: Double, val y: Double, val width: Double, val height: Double) {
    val right: Double get() = x + width
    val bottom: Double get() = y + height
}

/** Which origin a target point's normalised coordinates are relative to. */
@Serializable
enum class CoordinateSpace {
    /** Fractions of the full display; moving the trigger area does not move the point (default). */
    DISPLAY,

    /** Offsets relative to the trigger area's top-left corner, still in display fractions; moves with the area. */
    TRIGGER_RELATIVE,
}

@Serializable
enum class ExecutionMode {
    /** One injected tap per target, in order, with the configured delays between them. */
    SEQUENTIAL,

    /**
     * All enabled targets are sent as strokes of ONE gesture, i.e. genuine simultaneous multi-touch contacts
     * (`GestureDescription` supports up to [TriggerLimits.MAX_TARGETS] strokes). Per-target delays are ignored.
     */
    MULTI_TOUCH,
}

@Serializable
enum class TargetActionType {
    TAP,
    LONG_PRESS,
}

@Serializable
data class TargetPoint(
    val id: TargetPointId,
    val x: Double,
    val y: Double,
    val coordinateSpace: CoordinateSpace = CoordinateSpace.DISPLAY,
    val actionType: TargetActionType = TargetActionType.TAP,
    /** Sequential mode: wait this long *before* touching this point (0 for the first point is typical). */
    val delayBeforeMs: Long = TriggerLimits.DEFAULT_INTER_ACTION_DELAY_MS,
    /** Contact duration; defaults depend on [actionType] when null. */
    val holdMs: Long? = null,
    val enabled: Boolean = true,
) {
    val effectiveHoldMs: Long
        get() = holdMs ?: when (actionType) {
            TargetActionType.TAP -> TriggerLimits.DEFAULT_TAP_HOLD_MS
            TargetActionType.LONG_PRESS -> TriggerLimits.DEFAULT_LONG_PRESS_HOLD_MS
        }
}

/** Display the configuration was authored on; used for compatibility checks and for the numeric px editor. */
@Serializable
data class AuthoredDisplay(val widthPx: Int, val heightPx: Int, val orientation: DisplayOrientation) {
    val aspectRatio: Double get() = if (heightPx == 0) 0.0 else widthPx.toDouble() / heightPx.toDouble()
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TriggerConfiguration(
    val id: TriggerId,
    val name: String,
    val enabled: Boolean = true,
    /** Game/app profile binding: shown only while this package is in the foreground. `null` = armed manually. */
    val packageName: String? = null,
    val triggerArea: TriggerArea,
    val targetPoints: List<TargetPoint> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val executionMode: ExecutionMode = ExecutionMode.SEQUENTIAL,
    /** Trigger reaction delay: time between the activating touch-down and the first injected contact. */
    val reactionDelayMs: Long = TriggerLimits.DEFAULT_REACTION_DELAY_MS,
    /** Minimum time between two activations; also absorbs duplicate down events of one physical gesture. */
    val cooldownMs: Long = TriggerLimits.DEFAULT_COOLDOWN_MS,
    /** How many times the whole target sequence runs per activation (1 = once, see [repeatMode]). */
    val repeatCount: Int = 1,
    /** Repeat interval: pause between two runs of the sequence when [repeatCount] > 1. */
    val repeatDelayMs: Long = TriggerLimits.DEFAULT_REPEAT_DELAY_MS,
    val authoredDisplay: AuthoredDisplay,
    /** Per-configuration override of the global "show indicator during gameplay" setting. */
    val showIndicatorInGameplay: Boolean? = null,
) {
    val enabledTargets: List<TargetPoint> get() = targetPoints.filter { it.enabled }

    /** Derived from [repeatCount]; not stored separately so older documents stay valid. */
    val repeatMode: RepeatMode get() = if (repeatCount > 1) RepeatMode.FIXED_COUNT else RepeatMode.ONCE
}

/**
 * How often one activation runs the target sequence. "Repeat while held" is deliberately absent: dispatching a
 * gesture cancels the user's own touch on the overlay, so a continued hold cannot be observed with the
 * accessibility input mechanism (docs/phase-12 §4.4) – offering it would be a lie.
 */
enum class RepeatMode { ONCE, FIXED_COUNT }

/** Hard limits; mirrored in validation and in the editor. */
object TriggerLimits {
    /** `GestureDescription.getMaxStrokeCount()` is 10 on every API level we support; keep sequential the same. */
    const val MAX_TARGETS = 10
    const val MAX_NAME_LENGTH = 60
    const val MIN_AREA_FRACTION = 0.02
    const val MAX_DELAY_MS = 10_000L
    const val MAX_HOLD_MS = 5_000L
    const val MIN_HOLD_MS = 10L
    const val MAX_COOLDOWN_MS = 10_000L
    const val MIN_COOLDOWN_MS = 0L
    const val MAX_REPEAT = 20
    const val DEFAULT_INTER_ACTION_DELAY_MS = 50L
    const val DEFAULT_TAP_HOLD_MS = 50L
    const val DEFAULT_LONG_PRESS_HOLD_MS = 600L
    const val DEFAULT_COOLDOWN_MS = 150L
    const val DEFAULT_REACTION_DELAY_MS = 0L
    const val MAX_REACTION_DELAY_MS = 5_000L

    /** Quick-pick values offered by the editor; any value up to [MAX_REACTION_DELAY_MS] can be typed. */
    val REACTION_DELAY_PRESETS_MS: List<Long> = listOf(0L, 10L, 25L, 50L, 100L, 150L, 200L, 500L)
    const val DEFAULT_REPEAT_DELAY_MS = 100L

    /** Aspect ratio deviation above which the user is warned that coordinates may be off. */
    const val ASPECT_WARNING_TOLERANCE = 0.02
}
