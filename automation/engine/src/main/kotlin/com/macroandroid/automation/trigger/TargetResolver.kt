package com.macroandroid.automation.trigger

import com.macroandroid.core.common.display.DisplayGeometry
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode

/**
 * Turns a configuration into an [InjectionPlan] for the *current* display (§5, §15). Every check listed in the
 * validation section runs here again at activation time, because the display and the configuration may have
 * changed since the editor validated it.
 */
object TargetResolver {

    fun resolve(
        config: TriggerConfiguration,
        geometry: DisplayGeometry,
        capability: InjectionCapability,
        ignoreEnabledFlag: Boolean = false,
    ): AppResult<InjectionPlan> {
        if (!config.enabled && !ignoreEnabledFlag) return AppResult.err(ErrorCode.TRIGGER_DISABLED)
        if (!geometry.isValid) return AppResult.err(ErrorCode.DISPLAY_UNAVAILABLE)
        if (!capability.available) {
            return AppResult.err(capability.reason ?: ErrorCode.GESTURE_DISPATCH_UNAVAILABLE)
        }
        val structural = TriggerValidator.validate(config).filterNot { it.code == ErrorCode.NAME_INVALID }
        structural.firstOrNull()?.let { return AppResult.err(it) }
        val compatibility = CoordinateConverter.compatibility(config.authoredDisplay, geometry)
        if (compatibility == DisplayCompatibility.ORIENTATION_MISMATCH) {
            return AppResult.err(ErrorCode.DISPLAY_ORIENTATION_MISMATCH)
        }
        val contacts = ArrayList<Contact>()
        config.targetPoints.forEachIndexed { index, point ->
            if (!point.enabled) return@forEachIndexed
            val px = CoordinateConverter.pointToDisplay(point, config.triggerArea, geometry)
            if (!CoordinateConverter.isOnDisplay(px, geometry)) {
                return AppResult.err(
                    AppError(ErrorCode.TRIGGER_COORDINATE_INVALID, context = mapOf("target" to (index + 1).toString())),
                )
            }
            contacts += Contact(
                index = index + 1,
                x = px.x,
                y = px.y,
                holdMs = point.effectiveHoldMs,
                delayBeforeMs = point.delayBeforeMs,
            )
        }
        if (contacts.isEmpty()) return AppResult.err(ErrorCode.TRIGGER_NO_TARGETS)
        if (config.executionMode == ExecutionMode.MULTI_TOUCH && contacts.size > capability.maxSimultaneousContacts) {
            return AppResult.err(
                ErrorCode.LIMIT_EXCEEDED,
                detail = "multi-touch supports ${capability.maxSimultaneousContacts} contacts",
            )
        }
        return AppResult.ok(
            InjectionPlan(
                triggerId = config.id,
                mode = config.executionMode,
                contacts = contacts,
                repeatCount = config.repeatCount,
                repeatDelayMs = config.repeatDelayMs,
                compatibility = compatibility,
            ),
        )
    }
}
