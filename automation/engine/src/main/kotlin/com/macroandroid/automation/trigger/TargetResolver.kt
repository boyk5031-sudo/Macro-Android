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

    /**
     * @param ignoreEnabledFlag editor test runs: execute even when the configuration is switched off.
     * @param onlyPoint editor "test this point": a plan with just that point (its enabled flag ignored), run once,
     *   sequentially, without reaction delay or the point's own pre-delay. All other checks still apply.
     */
    fun resolve(
        config: TriggerConfiguration,
        geometry: DisplayGeometry,
        capability: InjectionCapability,
        ignoreEnabledFlag: Boolean = false,
        onlyPoint: TargetPointId? = null,
    ): AppResult<InjectionPlan> {
        precondition(config, geometry, capability, ignoreEnabledFlag, onlyPoint)?.let { return AppResult.err(it) }
        val compatibility = CoordinateConverter.compatibility(config.authoredDisplay, geometry)
        if (compatibility == DisplayCompatibility.ORIENTATION_MISMATCH) {
            return AppResult.err(ErrorCode.DISPLAY_ORIENTATION_MISMATCH)
        }
        val contacts = when (val resolved = contacts(config, geometry, onlyPoint)) {
            is AppResult.Ok -> resolved.value
            is AppResult.Err -> return resolved
        }
        if (config.executionMode == ExecutionMode.MULTI_TOUCH && onlyPoint == null &&
            contacts.size > capability.maxSimultaneousContacts
        ) {
            return AppResult.err(
                ErrorCode.LIMIT_EXCEEDED,
                detail = "multi-touch supports ${capability.maxSimultaneousContacts} contacts",
            )
        }
        return AppResult.ok(plan(config, contacts, compatibility, singlePoint = onlyPoint != null))
    }

    /** Access, display and structural checks; null when everything is fine. */
    private fun precondition(
        config: TriggerConfiguration,
        geometry: DisplayGeometry,
        capability: InjectionCapability,
        ignoreEnabledFlag: Boolean,
        onlyPoint: TargetPointId?,
    ): AppError? {
        if (!config.enabled && !ignoreEnabledFlag) return AppError(ErrorCode.TRIGGER_DISABLED)
        if (!geometry.isValid) return AppError(ErrorCode.DISPLAY_UNAVAILABLE)
        if (!capability.available) return AppError(capability.reason ?: ErrorCode.GESTURE_DISPATCH_UNAVAILABLE)
        val structural = TriggerValidator.validate(config)
            .filterNot { it.code == ErrorCode.NAME_INVALID }
            .filterNot { onlyPoint != null && it.code == ErrorCode.TRIGGER_NO_TARGETS }
        structural.firstOrNull()?.let { return it }
        if (onlyPoint != null && config.targetPoints.none { it.id == onlyPoint }) {
            return AppError(ErrorCode.TRIGGER_NO_TARGETS, detail = "point ${onlyPoint.value} not in configuration")
        }
        return null
    }

    /** Resolves the wanted points to physical pixels; an off-screen point fails the whole plan. */
    private fun contacts(
        config: TriggerConfiguration,
        geometry: DisplayGeometry,
        onlyPoint: TargetPointId?,
    ): AppResult<List<Contact>> {
        val contacts = ArrayList<Contact>()
        config.targetPoints.forEachIndexed { index, point ->
            val wanted = if (onlyPoint != null) point.id == onlyPoint else point.enabled
            if (!wanted) return@forEachIndexed
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
                delayBeforeMs = if (onlyPoint != null) 0L else point.delayBeforeMs,
            )
        }
        return if (contacts.isEmpty()) AppResult.err(ErrorCode.TRIGGER_NO_TARGETS) else AppResult.ok(contacts)
    }

    private fun plan(
        config: TriggerConfiguration,
        contacts: List<Contact>,
        compatibility: DisplayCompatibility,
        singlePoint: Boolean,
    ): InjectionPlan = if (singlePoint) {
        InjectionPlan(
            triggerId = config.id,
            mode = ExecutionMode.SEQUENTIAL,
            contacts = contacts,
            repeatCount = 1,
            repeatDelayMs = 0L,
            compatibility = compatibility,
            reactionDelayMs = 0L,
        )
    } else {
        InjectionPlan(
            triggerId = config.id,
            mode = config.executionMode,
            contacts = contacts,
            repeatCount = config.repeatCount,
            repeatDelayMs = config.repeatDelayMs,
            compatibility = compatibility,
            reactionDelayMs = config.reactionDelayMs,
        )
    }
}
