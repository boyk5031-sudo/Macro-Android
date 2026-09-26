package com.macroandroid.feature.trigger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macroandroid.automation.trigger.CoordinateConverter
import com.macroandroid.automation.trigger.TargetPointId
import com.macroandroid.automation.trigger.TriggerConfiguration
import com.macroandroid.core.common.display.DisplayGeometry
import com.macroandroid.core.common.display.DisplayRect

/** Which element a drag started on. */
private sealed interface DragTarget {
    data object None : DragTarget
    data object Area : DragTarget
    data object ResizeHandle : DragTarget
    data class Point(val id: TargetPointId) : DragTarget
}

/** Callbacks in normalised display fractions (deltas are fractions of width/height). */
class CanvasCallbacks(
    val onSelect: (TargetPointId?) -> Unit,
    val onAddPoint: (fx: Double, fy: Double) -> Unit,
    val onMoveArea: (dx: Double, dy: Double) -> Unit,
    val onResizeArea: (dw: Double, dh: Double) -> Unit,
    val onMovePoint: (TargetPointId, dx: Double, dy: Double) -> Unit,
)

/**
 * Scaled picture of the FULL display (same aspect ratio) in which the user places the trigger area and the
 * numbered target points. Canvas px → display fraction is a plain division by the canvas size; system bars and
 * cutouts are shaded so the user sees where taps would hit system UI instead of the game.
 */
@Composable
fun TriggerCanvas(
    config: TriggerConfiguration,
    geometry: DisplayGeometry,
    selectedId: TargetPointId?,
    callbacks: CanvasCallbacks,
    areaLabel: String,
    modifier: Modifier = Modifier,
    tapAddsPoint: Boolean = true,
) {
    val aspect = geometry.aspectRatio.toFloat().coerceIn(MIN_ASPECT, MAX_ASPECT)
    val latestConfig by rememberUpdatedState(config)
    val latestCallbacks by rememberUpdatedState(callbacks)
    val latestTapAdds by rememberUpdatedState(tapAddsPoint)
    val density = LocalDensity.current
    val pointRadiusPx = with(density) { POINT_RADIUS.toPx() }
    val handlePx = with(density) { HANDLE_SIZE.toPx() }
    val textMeasurer = rememberTextMeasurer()
    val colors = CanvasColors(
        background = MaterialTheme.colorScheme.surfaceContainerHighest,
        bars = MaterialTheme.colorScheme.onSurface.copy(alpha = BAR_ALPHA),
        areaFill = MaterialTheme.colorScheme.primary.copy(alpha = AREA_ALPHA),
        areaStroke = MaterialTheme.colorScheme.primary,
        point = MaterialTheme.colorScheme.tertiary,
        pointDisabled = MaterialTheme.colorScheme.outline,
        selected = MaterialTheme.colorScheme.error,
        onPoint = MaterialTheme.colorScheme.onTertiary,
        label = MaterialTheme.colorScheme.onSurface,
    )
    var dragTarget by remember { mutableStateOf<DragTarget>(DragTarget.None) }

    BoxWithConstraints(modifier.aspectRatio(aspect).testTag(TriggerTestTags.EDITOR_CANVAS)) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        fun hitTest(offset: Offset): DragTarget {
            val c = latestConfig
            c.targetPoints.asReversed().forEach { p ->
                val (fx, fy) = CoordinateConverter.toDisplayFraction(p, c.triggerArea)
                val center = Offset((fx * widthPx).toFloat(), (fy * heightPx).toFloat())
                if ((offset - center).getDistance() <= pointRadiusPx * HIT_SLOP) return DragTarget.Point(p.id)
            }
            val area = areaRect(c, widthPx, heightPx)
            val handle = Rect(area.right - handlePx, area.bottom - handlePx, area.right + handlePx / 2, area.bottom + handlePx / 2)
            if (handle.contains(offset)) return DragTarget.ResizeHandle
            if (area.contains(offset)) return DragTarget.Area
            return DragTarget.None
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        when (val hit = hitTest(offset)) {
                            is DragTarget.Point -> latestCallbacks.onSelect(hit.id)
                            DragTarget.Area, DragTarget.ResizeHandle -> latestCallbacks.onSelect(null)
                            DragTarget.None -> if (latestTapAdds) {
                                latestCallbacks.onAddPoint((offset.x / widthPx).toDouble(), (offset.y / heightPx).toDouble())
                            } else {
                                latestCallbacks.onSelect(null)
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragTarget = hitTest(offset)
                            (dragTarget as? DragTarget.Point)?.let { latestCallbacks.onSelect(it.id) }
                        },
                        onDragEnd = { dragTarget = DragTarget.None },
                        onDragCancel = { dragTarget = DragTarget.None },
                    ) { change, delta ->
                        change.consume()
                        val dx = (delta.x / widthPx).toDouble()
                        val dy = (delta.y / heightPx).toDouble()
                        when (val t = dragTarget) {
                            is DragTarget.Point -> latestCallbacks.onMovePoint(t.id, dx, dy)
                            DragTarget.Area -> latestCallbacks.onMoveArea(dx, dy)
                            DragTarget.ResizeHandle -> latestCallbacks.onResizeArea(dx, dy)
                            DragTarget.None -> Unit
                        }
                    }
                },
        ) {
            drawRect(colors.background)
            drawSystemRegions(geometry, colors)
            drawArea(config, colors, handlePx, textMeasurer, areaLabel)
            drawPoints(config, selectedId, colors, pointRadiusPx, textMeasurer)
        }
    }
}

private class CanvasColors(
    val background: Color,
    val bars: Color,
    val areaFill: Color,
    val areaStroke: Color,
    val point: Color,
    val pointDisabled: Color,
    val selected: Color,
    val onPoint: Color,
    val label: Color,
)

private fun areaRect(config: TriggerConfiguration, w: Float, h: Float): Rect {
    val a = config.triggerArea
    return Rect((a.x * w).toFloat(), (a.y * h).toFloat(), (a.right * w).toFloat(), (a.bottom * h).toFloat())
}

private fun DrawScope.drawSystemRegions(geometry: DisplayGeometry, colors: CanvasColors) {
    val sx = size.width / geometry.widthPx
    val sy = size.height / geometry.heightPx
    val bars = geometry.systemBars
    if (bars.top > 0) drawRect(colors.bars, Offset.Zero, Size(size.width, bars.top * sy))
    if (bars.bottom > 0) drawRect(colors.bars, Offset(0f, size.height - bars.bottom * sy), Size(size.width, bars.bottom * sy))
    if (bars.left > 0) drawRect(colors.bars, Offset.Zero, Size(bars.left * sx, size.height))
    if (bars.right > 0) drawRect(colors.bars, Offset(size.width - bars.right * sx, 0f), Size(bars.right * sx, size.height))
    geometry.cutouts.forEach { c: DisplayRect ->
        drawRect(colors.bars, Offset(c.left * sx, c.top * sy), Size(c.width * sx, c.height * sy))
    }
}

private fun DrawScope.drawArea(
    config: TriggerConfiguration,
    colors: CanvasColors,
    handlePx: Float,
    measurer: TextMeasurer,
    areaLabel: String,
) {
    val rect = areaRect(config, size.width, size.height)
    val radius = CornerRadius(handlePx / 2)
    drawRoundRect(colors.areaFill, rect.topLeft, rect.size, radius)
    drawRoundRect(colors.areaStroke, rect.topLeft, rect.size, radius, style = Stroke(width = handlePx / 6))
    drawRect(colors.areaStroke, Offset(rect.right - handlePx, rect.bottom - handlePx), Size(handlePx, handlePx))
    val label = measurer.measure(areaLabel, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.areaStroke))
    if (label.size.width < rect.width && label.size.height < rect.height) {
        drawText(label, topLeft = Offset(rect.center.x - label.size.width / 2, rect.center.y - label.size.height / 2))
    }
}

private fun DrawScope.drawPoints(
    config: TriggerConfiguration,
    selectedId: TargetPointId?,
    colors: CanvasColors,
    radius: Float,
    measurer: TextMeasurer,
) {
    config.targetPoints.forEachIndexed { index, p ->
        val (fx, fy) = CoordinateConverter.toDisplayFraction(p, config.triggerArea)
        val center = Offset((fx * size.width).toFloat(), (fy * size.height).toFloat())
        val fill = if (p.enabled) colors.point else colors.pointDisabled.copy(alpha = DISABLED_ALPHA)
        drawCircle(fill, radius, center)
        if (p.id == selectedId) drawCircle(colors.selected, radius + radius / 3, center, style = Stroke(width = radius / 4))
        val style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.onPoint)
        val text = measurer.measure((index + 1).toString(), style)
        drawText(text, topLeft = Offset(center.x - text.size.width / 2, center.y - text.size.height / 2))
    }
}

private val POINT_RADIUS = 14.dp
private val HANDLE_SIZE = 18.dp
private const val HIT_SLOP = 1.6f
private const val MIN_ASPECT = 0.3f
private const val MAX_ASPECT = 3.5f
private const val BAR_ALPHA = 0.12f
private const val AREA_ALPHA = 0.25f
private const val DISABLED_ALPHA = 0.5f
