package com.macroandroid.automation.trigger

import com.macroandroid.core.common.display.DisplayGeometry
import com.macroandroid.core.common.display.DisplayRect
import kotlin.math.abs
import kotlin.math.hypot

/** What a finger grabbed on the on-screen editor. */
sealed interface EditHandle {
    data object None : EditHandle
    data object AreaMove : EditHandle
    data object AreaResize : EditHandle
    data class Point(val id: TargetPointId) : EditHandle
}

/** What happened when the finger lifted. */
sealed interface EditGesture {
    /** A tap on empty space (no handle) – the caller may add a point here. */
    data class TapEmpty(val xPx: Float, val yPx: Float) : EditGesture

    /** Tap on a point without dragging: it is now selected. */
    data class PointSelected(val id: TargetPointId) : EditGesture

    /** A drag finished; [changed] is false when the drag stayed within the touch slop. */
    data class Dragged(val handle: EditHandle, val changed: Boolean) : EditGesture

    data object Idle : EditGesture
}

/**
 * Pure state machine behind the on-screen edit mode (§13 "Edit Mode"): the Android overlay forwards raw touches
 * in physical pixels, this class turns them into configuration edits in normalised space using the same
 * [CoordinateConverter] as gameplay. Hit priority on DOWN: point marker (nearest within [handleRadiusPx]) →
 * area resize handle (bottom-right corner) → area body (move) → nothing.
 *
 * Moving the area never moves `DISPLAY`-space points; `TRIGGER_RELATIVE` points follow it because their stored
 * coordinates are offsets (§4). Everything is clamped to the display; the area keeps its minimum size.
 */
class TriggerEditSession(
    initial: TriggerConfiguration,
    geometry: DisplayGeometry,
    private val handleRadiusPx: Float,
    private val touchSlopPx: Float,
) {
    var config: TriggerConfiguration = initial
        private set
    var geometry: DisplayGeometry = geometry
        private set
    var selectedPointId: TargetPointId? = null
        private set
    var grabbed: EditHandle = EditHandle.None
        private set

    private val original = initial
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    val isDirty: Boolean get() = config != original
    val isDragging: Boolean get() = grabbed != EditHandle.None && moved

    fun updateGeometry(geometry: DisplayGeometry) {
        this.geometry = geometry
        cancelGesture()
    }

    fun onDown(xPx: Float, yPx: Float) {
        downX = xPx
        downY = yPx
        lastX = xPx
        lastY = yPx
        moved = false
        grabbed = hitTest(xPx, yPx)
    }

    fun onMove(xPx: Float, yPx: Float) {
        val handle = grabbed
        if (handle == EditHandle.None) return
        if (!moved && hypot(xPx - downX, yPx - downY) < touchSlopPx) return
        moved = true
        val dx = (xPx - lastX).toDouble() / geometry.widthPx
        val dy = (yPx - lastY).toDouble() / geometry.heightPx
        lastX = xPx
        lastY = yPx
        when (handle) {
            EditHandle.AreaMove -> moveArea(dx, dy)
            EditHandle.AreaResize -> resizeArea(dx, dy)
            is EditHandle.Point -> movePoint(handle.id, dx, dy)
            EditHandle.None -> Unit
        }
    }

    fun onUp(xPx: Float, yPx: Float): EditGesture {
        val handle = grabbed
        val wasMoved = moved
        grabbed = EditHandle.None
        moved = false
        return when {
            handle == EditHandle.None && !wasMoved -> EditGesture.TapEmpty(xPx, yPx)
            handle is EditHandle.Point && !wasMoved -> {
                selectedPointId = handle.id
                EditGesture.PointSelected(handle.id)
            }
            handle != EditHandle.None -> EditGesture.Dragged(handle, wasMoved)
            else -> EditGesture.Idle
        }
    }

    /** ACTION_CANCEL: keep whatever was already applied, just stop tracking the finger. */
    fun cancelGesture() {
        grabbed = EditHandle.None
        moved = false
    }

    /** Adds a DISPLAY-space point at a physical position; false when the cap is reached. */
    fun addPointAt(xPx: Float, yPx: Float): Boolean {
        if (config.targetPoints.size >= TriggerLimits.MAX_TARGETS) return false
        val (fx, fy) = CoordinateConverter.displayToFraction(xPx.toDouble(), yPx.toDouble(), geometry)
        val point = TargetPoint(id = TargetPointId.random(), x = fx.coerceIn(0.0, 1.0), y = fy.coerceIn(0.0, 1.0))
        config = config.copy(targetPoints = config.targetPoints + point)
        selectedPointId = point.id
        return true
    }

    fun deleteSelected(): Boolean {
        val id = selectedPointId ?: return false
        config = config.copy(targetPoints = config.targetPoints.filterNot { it.id == id })
        selectedPointId = null
        return true
    }

    fun toggleSelectedEnabled(): Boolean {
        val id = selectedPointId ?: return false
        config = config.copy(targetPoints = config.targetPoints.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
        return true
    }

    fun select(id: TargetPointId?) {
        selectedPointId = id?.takeIf { pid -> config.targetPoints.any { it.id == pid } }
    }

    /** Physical positions for drawing; the same conversion gameplay uses, so what you see is what gets tapped. */
    fun pointPositions(): List<Pair<TargetPoint, DisplayPoint>> =
        config.targetPoints.map { it to CoordinateConverter.pointToDisplay(it, config.triggerArea, geometry) }

    fun areaBounds(): DisplayRect = CoordinateConverter.areaToDisplay(config.triggerArea, geometry)

    // ---- internals ----------------------------------------------------------------------------------------

    fun hitTest(xPx: Float, yPx: Float): EditHandle {
        val nearest = pointPositions()
            .map { (p, pos) -> p.id to hypot(pos.x - xPx, pos.y - yPx) }
            .filter { it.second <= handleRadiusPx }
            .minByOrNull { it.second }
        if (nearest != null) return EditHandle.Point(nearest.first)
        val bounds = areaBounds()
        val nearCorner = abs(bounds.right - xPx) <= handleRadiusPx && abs(bounds.bottom - yPx) <= handleRadiusPx
        if (nearCorner) return EditHandle.AreaResize
        if (bounds.contains(xPx, yPx)) return EditHandle.AreaMove
        return EditHandle.None
    }

    private fun moveArea(dx: Double, dy: Double) {
        val a = config.triggerArea
        val nx = (a.x + dx).coerceIn(0.0, (1.0 - a.width).coerceAtLeast(0.0))
        val ny = (a.y + dy).coerceIn(0.0, (1.0 - a.height).coerceAtLeast(0.0))
        config = config.copy(triggerArea = a.copy(x = nx, y = ny))
    }

    private fun resizeArea(dw: Double, dh: Double) {
        val a = config.triggerArea
        val nw = (a.width + dw).coerceIn(TriggerLimits.MIN_AREA_FRACTION, 1.0 - a.x)
        val nh = (a.height + dh).coerceIn(TriggerLimits.MIN_AREA_FRACTION, 1.0 - a.y)
        config = config.copy(triggerArea = a.copy(width = nw, height = nh))
    }

    private fun movePoint(id: TargetPointId, dx: Double, dy: Double) {
        val area = config.triggerArea
        config = config.copy(
            targetPoints = config.targetPoints.map { p ->
                if (p.id != id) return@map p
                val (fx, fy) = CoordinateConverter.toDisplayFraction(p, area)
                val (nx, ny) = CoordinateConverter.fractionToSpace(
                    (fx + dx).coerceIn(0.0, 1.0),
                    (fy + dy).coerceIn(0.0, 1.0),
                    p.coordinateSpace,
                    area,
                )
                p.copy(x = nx, y = ny)
            },
        )
    }
}
