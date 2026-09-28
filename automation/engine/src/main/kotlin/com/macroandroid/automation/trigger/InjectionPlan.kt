package com.macroandroid.automation.trigger

import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode

/** One touch contact to inject, fully resolved to physical pixels. */
data class Contact(
    /** 1-based target number as shown in the editor, for logs. */
    val index: Int,
    val x: Float,
    val y: Float,
    val holdMs: Long,
    /** Sequential mode only: wait before this contact. */
    val delayBeforeMs: Long,
)

/** Resolved, validated actions for one activation. */
data class InjectionPlan(
    val triggerId: TriggerId,
    val mode: ExecutionMode,
    val contacts: List<Contact>,
    val repeatCount: Int,
    val repeatDelayMs: Long,
    val compatibility: DisplayCompatibility,
    /** Waited once, before the first contact of the first run. */
    val reactionDelayMs: Long = 0L,
) {
    val isEmpty: Boolean get() = contacts.isEmpty()

    /** Upper bound of wall time, used for the injection timeout guard. */
    val estimatedDurationMs: Long
        get() {
            val perRun = when (mode) {
                ExecutionMode.SEQUENTIAL -> contacts.sumOf { it.delayBeforeMs + it.holdMs }
                ExecutionMode.MULTI_TOUCH -> contacts.maxOfOrNull { it.holdMs } ?: 0L
            }
            return reactionDelayMs + perRun * repeatCount + repeatDelayMs * (repeatCount - 1).coerceAtLeast(0)
        }
}

/** What the injection adapter can currently do; re-evaluated on every activation (never cached across binds). */
data class InjectionCapability(
    val available: Boolean,
    val maxSimultaneousContacts: Int,
    /** Why [available] is false. */
    val reason: ErrorCode? = null,
    /**
     * True only if injected contacts can coexist with a finger the user already has on the screen. False for
     * `dispatchGesture`, which cancels the user's in-progress gesture and is itself cancelled by the next real
     * touch event (platform behaviour, see docs/phase-12-trigger-areas.md §12).
     */
    val coexistsWithUserTouch: Boolean = false,
)

/**
 * Abstraction over the Android input mechanism (§10). The only production implementation uses
 * `AccessibilityService.dispatchGesture`; tests use a recording fake. Implementations must guarantee that every
 * contact they put down is lifted (or cancelled) before returning, so no stuck pointers can remain.
 */
interface InputInjectionAdapter {
    fun capability(): InjectionCapability

    /**
     * Injects [contacts] as ONE gesture: all contacts go down together and each lifts after its own `holdMs`.
     * With a single contact this is a plain tap / long press. Suspends until the system reports completion.
     */
    suspend fun inject(contacts: List<Contact>): AppResult<Unit>

    /** Best-effort cancellation of an in-flight gesture (used on disarm/teardown). */
    suspend fun cancelAll()
}
