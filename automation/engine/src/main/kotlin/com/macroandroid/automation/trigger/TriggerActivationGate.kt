package com.macroandroid.automation.trigger

/** Why a touch-down did not activate the trigger. */
enum class GateRejection {
    /** A pointer is already down (multi-finger or duplicate DOWN without UP). */
    ALREADY_HELD,
    COOLDOWN,
}

sealed interface GateDecision {
    data object Activate : GateDecision
    data class Reject(val reason: GateRejection) : GateDecision
}

/**
 * Touch-down activation with touch-up reset and cooldown (§7). Pure and single-threaded by contract: the overlay
 * calls it from the main thread; tests drive it with a fake clock.
 *
 * Rules: one activation per physical press (DOWN → activation, UP/CANCEL → reset, further DOWNs while held are
 * ignored) and never two activations closer than [cooldownMs], even across separate presses.
 */
class TriggerActivationGate(private val cooldownMs: Long) {
    private var held = false
    private var lastActivationAt: Long? = null

    val isHeld: Boolean get() = held

    fun onDown(nowMs: Long): GateDecision {
        if (held) return GateDecision.Reject(GateRejection.ALREADY_HELD)
        held = true
        val last = lastActivationAt
        if (last != null && nowMs - last < cooldownMs) return GateDecision.Reject(GateRejection.COOLDOWN)
        lastActivationAt = nowMs
        return GateDecision.Activate
    }

    /** UP or CANCEL: the press is over; the next DOWN may activate again (subject to cooldown). */
    fun onUp() {
        held = false
    }

    fun reset() {
        held = false
        lastActivationAt = null
    }
}
