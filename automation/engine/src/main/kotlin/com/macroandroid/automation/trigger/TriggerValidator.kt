package com.macroandroid.automation.trigger

import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.ErrorCode

/** Structural validation of a configuration, independent of the current display (§6). */
object TriggerValidator {

    fun validate(config: TriggerConfiguration): List<AppError> = buildList {
        if (config.name.isBlank() || config.name.length > TriggerLimits.MAX_NAME_LENGTH) {
            add(AppError(ErrorCode.NAME_INVALID, context = mapOf("field" to "name")))
        }
        if (!areaValid(config.triggerArea)) add(AppError(ErrorCode.TRIGGER_AREA_INVALID))
        if (config.targetPoints.size > TriggerLimits.MAX_TARGETS) {
            add(AppError(ErrorCode.LIMIT_EXCEEDED, detail = "targets>${TriggerLimits.MAX_TARGETS}"))
        }
        if (config.enabledTargets.isEmpty()) add(AppError(ErrorCode.TRIGGER_NO_TARGETS))
        config.targetPoints.forEachIndexed { index, p ->
            val (fx, fy) = CoordinateConverter.toDisplayFraction(p, config.triggerArea)
            if (!fx.isFinite() || !fy.isFinite() || fx !in 0.0..1.0 || fy !in 0.0..1.0) {
                add(AppError(ErrorCode.TRIGGER_COORDINATE_INVALID, context = mapOf("target" to (index + 1).toString())))
            }
            if (p.delayBeforeMs !in 0..TriggerLimits.MAX_DELAY_MS ||
                p.effectiveHoldMs !in TriggerLimits.MIN_HOLD_MS..TriggerLimits.MAX_HOLD_MS
            ) {
                add(AppError(ErrorCode.TRIGGER_TIMING_RANGE, context = mapOf("target" to (index + 1).toString())))
            }
        }
        if (config.cooldownMs !in TriggerLimits.MIN_COOLDOWN_MS..TriggerLimits.MAX_COOLDOWN_MS ||
            config.repeatCount !in 1..TriggerLimits.MAX_REPEAT ||
            config.repeatDelayMs !in 0..TriggerLimits.MAX_DELAY_MS
        ) {
            add(AppError(ErrorCode.TRIGGER_TIMING_RANGE, context = mapOf("field" to "timing")))
        }
        if (config.authoredDisplay.widthPx <= 0 || config.authoredDisplay.heightPx <= 0) {
            add(AppError(ErrorCode.DISPLAY_UNAVAILABLE, detail = "authoredDisplay"))
        }
        if (config.packageName != null && !PACKAGE_REGEX.matches(config.packageName)) {
            add(AppError(ErrorCode.PACKAGE_NAME_INVALID))
        }
    }

    fun areaValid(area: TriggerArea): Boolean =
        listOf(area.x, area.y, area.width, area.height).all { it.isFinite() } &&
            area.x >= 0.0 && area.y >= 0.0 &&
            area.width >= TriggerLimits.MIN_AREA_FRACTION && area.height >= TriggerLimits.MIN_AREA_FRACTION &&
            area.right <= 1.0 + EPSILON && area.bottom <= 1.0 + EPSILON

    private const val EPSILON = 1e-9
    private val PACKAGE_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
}
