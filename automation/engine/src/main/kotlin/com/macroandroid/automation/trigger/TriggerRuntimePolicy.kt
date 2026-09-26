package com.macroandroid.automation.trigger

import com.macroandroid.core.common.display.DisplayGeometry
import com.macroandroid.core.common.error.ErrorCode

/** Everything the runtime knows at one instant; the policy is a pure function of it (§11, §14). */
data class RuntimeInput(
    val configurations: List<TriggerConfiguration>,
    val foregroundPackage: String?,
    val ownPackage: String,
    val manuallyArmedId: TriggerId?,
    val accessReady: Boolean,
    val screenInteractive: Boolean,
    val geometry: DisplayGeometry?,
)

data class RuntimeDecision(
    /** Configurations whose overlay must be visible right now, in stable order. */
    val visible: List<TriggerConfiguration>,
    /** Why an otherwise-armed configuration is hidden; null when idle or visible. */
    val blockedReason: ErrorCode?,
) {
    companion object {
        val IDLE = RuntimeDecision(emptyList(), null)
    }
}

/**
 * Decides which trigger overlays exist. Rules, in order:
 *  1. Nothing is shown over our own app (the editor has its own preview; test runs need no overlay).
 *  2. Candidates: enabled configurations bound to the foreground package, plus the manually armed one
 *     (only configurations without a package binding can be armed manually).
 *  3. All access requirements must hold, the screen must be interactive and the display measurable.
 *  4. Configurations authored in another orientation are suspended, not shown.
 */
object TriggerRuntimePolicy {
    fun decide(input: RuntimeInput): RuntimeDecision {
        if (input.foregroundPackage == input.ownPackage) return RuntimeDecision.IDLE
        val candidates = input.configurations.filter { c ->
            c.enabled && (
                (c.packageName != null && c.packageName == input.foregroundPackage) ||
                    (c.packageName == null && c.id == input.manuallyArmedId)
                )
        }
        if (candidates.isEmpty()) return RuntimeDecision.IDLE
        if (!input.accessReady) return RuntimeDecision(emptyList(), ErrorCode.GESTURE_DISPATCH_UNAVAILABLE)
        if (!input.screenInteractive) return RuntimeDecision(emptyList(), ErrorCode.PRECONDITION_SCREEN_OFF)
        val geometry = input.geometry
        if (geometry == null || !geometry.isValid) return RuntimeDecision(emptyList(), ErrorCode.DISPLAY_UNAVAILABLE)
        val oriented = candidates.filter { c ->
            CoordinateConverter.compatibility(c.authoredDisplay, geometry) != DisplayCompatibility.ORIENTATION_MISMATCH
        }
        if (oriented.isEmpty()) return RuntimeDecision(emptyList(), ErrorCode.DISPLAY_ORIENTATION_MISMATCH)
        return RuntimeDecision(oriented, null)
    }
}
