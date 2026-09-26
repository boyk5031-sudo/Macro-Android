package com.macroandroid.automation.trigger

import com.macroandroid.core.common.display.DisplayGeometry
import com.macroandroid.core.common.display.DisplayRect
import kotlin.math.abs
import kotlin.math.roundToInt

/** A point in physical display pixels (the space `dispatchGesture` and accessibility overlays use). */
data class DisplayPoint(val x: Float, val y: Float)

/** Result of comparing the authored display with the current one. */
enum class DisplayCompatibility {
    /** Same orientation and (within tolerance) same aspect ratio: coordinates map exactly. */
    EXACT,

    /** Same orientation, different aspect ratio: normalised coordinates still map, but the user should re-check. */
    ASPECT_DIFFERS,

    /** Different orientation: the configuration is suspended until the device is rotated back. */
    ORIENTATION_MISMATCH,
}

/**
 * The single conversion layer between the stored normalised space and physical pixels (§4).
 *
 *  Editor canvas px  ──(canvas scale)──▶  normalised (0..1 of full display)  ──(this)──▶  physical display px
 *
 * Insets, cutouts and navigation bars do not shift anything: normalised coordinates are fractions of the FULL
 * display, which is exactly what input injection expects. They matter only for the editor, which shades those
 * regions so the user does not place points under a bar.
 */
object CoordinateConverter {

    fun compatibility(authored: AuthoredDisplay, current: DisplayGeometry): DisplayCompatibility = when {
        authored.orientation != current.orientation -> DisplayCompatibility.ORIENTATION_MISMATCH
        abs(authored.aspectRatio - current.aspectRatio) > TriggerLimits.ASPECT_WARNING_TOLERANCE ->
            DisplayCompatibility.ASPECT_DIFFERS
        else -> DisplayCompatibility.EXACT
    }

    /** Trigger area in physical pixels, clamped to the display so an off-screen area cannot create a bad window. */
    fun areaToDisplay(area: TriggerArea, geometry: DisplayGeometry): DisplayRect {
        val left = (area.x * geometry.widthPx).roundToInt().coerceIn(0, geometry.widthPx)
        val top = (area.y * geometry.heightPx).roundToInt().coerceIn(0, geometry.heightPx)
        val right = (area.right * geometry.widthPx).roundToInt().coerceIn(left, geometry.widthPx)
        val bottom = (area.bottom * geometry.heightPx).roundToInt().coerceIn(top, geometry.heightPx)
        return DisplayRect(left, top, right, bottom)
    }

    /** Normalised coordinates of a point in [CoordinateSpace.DISPLAY] terms, resolving trigger-relative points. */
    fun toDisplayFraction(point: TargetPoint, area: TriggerArea): Pair<Double, Double> = when (point.coordinateSpace) {
        CoordinateSpace.DISPLAY -> point.x to point.y
        CoordinateSpace.TRIGGER_RELATIVE -> (area.x + point.x) to (area.y + point.y)
    }

    /** Physical pixel position of a target point on [geometry]; not clamped – validation rejects off-screen points. */
    fun pointToDisplay(point: TargetPoint, area: TriggerArea, geometry: DisplayGeometry): DisplayPoint {
        val (fx, fy) = toDisplayFraction(point, area)
        return DisplayPoint((fx * geometry.widthPx).toFloat(), (fy * geometry.heightPx).toFloat())
    }

    /** Inverse mapping used by the editor and by "edit X/Y in pixels" fields. */
    fun displayToFraction(xPx: Double, yPx: Double, geometry: DisplayGeometry): Pair<Double, Double> =
        (xPx / geometry.widthPx) to (yPx / geometry.heightPx)

    /** Converts a display-space fraction into the point's own coordinate space. */
    fun fractionToSpace(fx: Double, fy: Double, space: CoordinateSpace, area: TriggerArea): Pair<Double, Double> =
        when (space) {
            CoordinateSpace.DISPLAY -> fx to fy
            CoordinateSpace.TRIGGER_RELATIVE -> (fx - area.x) to (fy - area.y)
        }

    fun isOnDisplay(point: DisplayPoint, geometry: DisplayGeometry): Boolean =
        point.x >= 0f && point.y >= 0f && point.x < geometry.widthPx && point.y < geometry.heightPx
}

/** Pure hit test: is a physical touch inside the trigger area on this display? */
object TriggerHitTester {
    fun hit(area: TriggerArea, geometry: DisplayGeometry, xPx: Float, yPx: Float): Boolean =
        CoordinateConverter.areaToDisplay(area, geometry).contains(xPx, yPx)
}
