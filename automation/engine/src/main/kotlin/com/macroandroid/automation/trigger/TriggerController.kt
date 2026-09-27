package com.macroandroid.automation.trigger

import com.macroandroid.core.common.display.DisplayGeometry
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.common.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class TouchKind { DOWN, UP, CANCEL }

/** A raw touch on the overlay, already in physical display pixels. */
data class TriggerTouch(val kind: TouchKind, val xPx: Float, val yPx: Float, val atMs: Long)

sealed interface TouchOutcome {
    /** Trigger fired; [plan] is being executed asynchronously. */
    data class Activated(val plan: InjectionPlan) : TouchOutcome

    /** Touch outside the area or a non-DOWN event; nothing happens. */
    data object Ignored : TouchOutcome

    /** DOWN inside the area, but not executed for [reason] (cooldown, busy, resolver error…). */
    data class Rejected(val reason: ErrorCode) : TouchOutcome
}

/** Why an execution ran; test runs are labelled in logs and never counted as gameplay activations. */
enum class ExecutionReason { TRIGGER, TEST }

/**
 * Explicit trigger state (§11). Transitions: IDLE ─DOWN inside─▶ EXECUTING (reaction delay, targets, repeats)
 * ─done─▶ WAITING_FOR_RELEASE or COOLDOWN ─UP/CANCEL + cooldown elapsed─▶ IDLE. A DOWN in any state but IDLE is
 * rejected, which is what guarantees one physical gesture never activates twice.
 */
enum class TriggerState {
    IDLE,

    /** Reaction delay or target injection in progress. */
    EXECUTING,

    /** Execution finished but the activating finger is still down (or the CANCEL is still pending). */
    WAITING_FOR_RELEASE,

    /** Released, but the cooldown since the last activation has not elapsed. */
    COOLDOWN,
}

/**
 * Trigger Detection → Trigger Controller → Target Point Resolver → Coordinate Converter → Input Injection Adapter.
 *
 * One instance per armed configuration. Pure Kotlin: the Android layer feeds [onTouch] from the overlay view and
 * updates [geometry] on display changes. The touch position is used ONLY for the hit test – targets always come
 * from the configuration (§1, §4).
 */
class TriggerController(
    @Volatile var config: TriggerConfiguration,
    @Volatile var geometry: DisplayGeometry,
    private val adapter: InputInjectionAdapter,
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val onExecuted: (TriggerId, AppResult<Unit>) -> Unit = { _, _ -> },
) {
    private val gate = TriggerActivationGate(config.cooldownMs)
    private var job: Job? = null

    val isExecuting: Boolean get() = job?.isActive == true

    fun state(nowMs: Long): TriggerState = when {
        isExecuting -> TriggerState.EXECUTING
        gate.isHeld -> TriggerState.WAITING_FOR_RELEASE
        gate.inCooldown(nowMs) -> TriggerState.COOLDOWN
        else -> TriggerState.IDLE
    }

    fun onTouch(touch: TriggerTouch): TouchOutcome {
        when (touch.kind) {
            TouchKind.UP, TouchKind.CANCEL -> {
                gate.onUp()
                return TouchOutcome.Ignored
            }
            TouchKind.DOWN -> Unit
        }
        // MOVE events never reach here: the overlay forwards DOWN/UP/CANCEL only (§11).
        if (!TriggerHitTester.hit(config.triggerArea, geometry, touch.xPx, touch.yPx)) return TouchOutcome.Ignored
        when (val decision = gate.onDown(touch.atMs)) {
            is GateDecision.Reject -> return TouchOutcome.Rejected(
                when (decision.reason) {
                    GateRejection.ALREADY_HELD -> ErrorCode.TRIGGER_BUSY
                    GateRejection.COOLDOWN -> ErrorCode.TRIGGER_COOLDOWN
                },
            )
            GateDecision.Activate -> Unit
        }
        if (isExecuting) return TouchOutcome.Rejected(ErrorCode.TRIGGER_BUSY)
        val plan = when (val resolved = TargetResolver.resolve(config, geometry, adapter.capability())) {
            is AppResult.Ok -> resolved.value
            is AppResult.Err -> {
                logger.w(TAG, "trigger ${config.id} rejected: ${resolved.error.code}")
                return TouchOutcome.Rejected(resolved.error.code)
            }
        }
        logActivation(touch, plan)
        job = scope.launch {
            val result = TriggerPlanExecutor.execute(plan, adapter, logger, ExecutionReason.TRIGGER)
            onExecuted(config.id, result)
        }
        return TouchOutcome.Activated(plan)
    }

    /** Cancels any in-flight execution and forgets pointer state; the adapter lifts injected contacts. */
    suspend fun disarm() {
        job?.cancel()
        job = null
        gate.reset()
        adapter.cancelAll()
    }

    private fun logActivation(touch: TriggerTouch, plan: InjectionPlan) {
        val bounds = CoordinateConverter.areaToDisplay(config.triggerArea, geometry)
        logger.d(
            TAG,
            buildString {
                append("Trigger detected\nTrigger ID: ").append(config.id)
                append("\nTouch: ").append(touch.xPx.toInt()).append(',').append(touch.yPx.toInt())
                append("\nTrigger bounds: ").append(bounds.left).append(',').append(bounds.top).append(',')
                append(bounds.width).append(',').append(bounds.height)
                append("\nReaction delay: ").append(plan.reactionDelayMs).append(" ms")
                append("\nTarget count: ").append(plan.contacts.size)
                plan.contacts.forEach { c ->
                    append("\nTarget ").append(c.index).append(": ").append(c.x.toInt()).append(',').append(c.y.toInt())
                    append(" delay ").append(c.delayBeforeMs).append(" ms hold ").append(c.holdMs).append(" ms")
                }
                append("\nExecution mode: ").append(plan.mode)
                append("\nCooldown: ").append(config.cooldownMs).append(" ms")
                append("\nRepeat: ").append(config.repeatMode).append(" ×").append(plan.repeatCount)
                append(" every ").append(plan.repeatDelayMs).append(" ms")
                append("\nInjection: ").append(adapter.capability().let { if (it.available) "available" else "${it.reason}" })
                if (plan.compatibility != DisplayCompatibility.EXACT) append("\nDisplay: ").append(plan.compatibility)
            },
        )
    }

    private companion object {
        const val TAG = "Trigger"
    }
}

/** Runs an [InjectionPlan] through the adapter; shared by gameplay activations and editor test runs. */
object TriggerPlanExecutor {
    private const val TAG = "Trigger"

    suspend fun execute(
        plan: InjectionPlan,
        adapter: InputInjectionAdapter,
        logger: Logger,
        reason: ExecutionReason,
    ): AppResult<Unit> {
        if (plan.isEmpty) return AppResult.err(ErrorCode.TRIGGER_NO_TARGETS)
        if (plan.reactionDelayMs > 0) delay(plan.reactionDelayMs)
        repeat(plan.repeatCount) { run ->
            if (run > 0 && plan.repeatDelayMs > 0) delay(plan.repeatDelayMs)
            val result = when (plan.mode) {
                ExecutionMode.SEQUENTIAL -> sequential(plan, adapter)
                ExecutionMode.MULTI_TOUCH -> adapter.inject(plan.contacts)
            }
            if (result is AppResult.Err) {
                logger.w(TAG, "$reason ${plan.triggerId} run ${run + 1}/${plan.repeatCount} failed: ${result.error.code}")
                return result
            }
        }
        logger.d(TAG, "$reason ${plan.triggerId} completed (${plan.contacts.size} targets × ${plan.repeatCount})")
        return AppResult.ok(Unit)
    }

    private suspend fun sequential(plan: InjectionPlan, adapter: InputInjectionAdapter): AppResult<Unit> {
        for (contact in plan.contacts) {
            if (contact.delayBeforeMs > 0) delay(contact.delayBeforeMs)
            val result = adapter.inject(listOf(contact))
            if (result is AppResult.Err) return result
        }
        return AppResult.ok(Unit)
    }
}
