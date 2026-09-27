package com.macroandroid.automation.android.trigger

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.macroandroid.automation.android.R
import com.macroandroid.automation.trigger.EditGesture
import com.macroandroid.automation.trigger.EditHandle
import com.macroandroid.automation.trigger.TriggerConfiguration
import com.macroandroid.automation.trigger.TriggerEditSession
import com.macroandroid.automation.trigger.TriggerLimits
import com.macroandroid.core.common.display.DisplayGeometry

/** Toolbar commands of the on-screen editor; only TEST, CANCEL and DONE leave the view. */
enum class EditCommand { ADD, DELETE, TOGGLE, TEST, CANCEL, DONE }

/**
 * Full-display `TYPE_ACCESSIBILITY_OVERLAY` view for edit mode (§13): shows the trigger area with a resize
 * handle, numbered target markers (hollow when disabled, highlighted when selected) and a small toolbar. All
 * geometry decisions live in [TriggerEditSession]; this view only draws and forwards touches. It never injects
 * anything itself – TEST is delegated to the runtime, which makes this window untouchable for the duration.
 */
@SuppressLint("ViewConstructor") // created programmatically by TriggerOverlayController only
class TriggerEditOverlayView(
    context: Context,
    val session: TriggerEditSession,
    private val onCommand: (EditCommand) -> Unit,
) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val areaFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(AREA_FILL_ALPHA, 0x4C, 0xAF, 0x50) }
    private val areaStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x4C, 0xAF, 0x50)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pointFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x21, 0x96, 0xF3) }
    private val pointDisabled = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x90, 0xA4, 0xAE)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val pointSelected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xFF, 0xC1, 0x07)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = LABEL_SP * density
        textAlign = Paint.Align.CENTER
        setShadowLayer(2f * density, 0f, 0f, Color.BLACK)
    }
    private val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(BAR_ALPHA, 0x10, 0x10, 0x10) }
    private val buttonBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(BUTTON_ALPHA, 0xFF, 0xFF, 0xFF) }
    private val buttonPrimary = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x4C, 0xAF, 0x50) }
    private val buttonText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = BUTTON_SP * density
        textAlign = Paint.Align.CENTER
    }
    private val hintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = HINT_SP * density
        setShadowLayer(2f * density, 0f, 0f, Color.BLACK)
    }

    private class Button(val command: EditCommand, val text: String, val rect: RectF = RectF(), val primary: Boolean = false)

    private val buttons = listOf(
        Button(EditCommand.ADD, context.getString(R.string.automation_android_trigger_edit_add)),
        Button(EditCommand.DELETE, context.getString(R.string.automation_android_trigger_edit_delete)),
        Button(EditCommand.TOGGLE, context.getString(R.string.automation_android_trigger_edit_toggle)),
        Button(EditCommand.TEST, context.getString(R.string.automation_android_trigger_edit_test)),
        Button(EditCommand.CANCEL, context.getString(R.string.automation_android_trigger_edit_cancel)),
        Button(EditCommand.DONE, context.getString(R.string.automation_android_trigger_edit_done), primary = true),
    )
    private val hint = context.getString(R.string.automation_android_trigger_edit_hint)
    private val barRect = RectF()
    private val tmp = RectF()
    private var pressedButton: Button? = null

    /** Ring around markers uses the same radius the session uses for hit testing. */
    val handleRadiusPx: Float get() = HANDLE_DP * density

    var busy: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun onGeometryChanged(geometry: DisplayGeometry) {
        session.updateGeometry(geometry)
        invalidate()
    }

    val config: TriggerConfiguration get() = session.config

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutToolbar(w)
    }

    private fun layoutToolbar(w: Int) {
        val top = session.geometry.systemBars.top.toFloat() + PAD_DP * density
        val height = BUTTON_H_DP * density
        barRect.set(0f, top - PAD_DP * density, w.toFloat(), top + height + PAD_DP * density + HINT_SP * density * 2)
        val gap = PAD_DP * density
        val bw = (w - gap * (buttons.size + 1)) / buttons.size
        buttons.forEachIndexed { i, b -> b.rect.set(gap + i * (bw + gap), top, gap + i * (bw + gap) + bw, top + height) }
    }

    override fun onDraw(canvas: Canvas) {
        val bounds = session.areaBounds()
        tmp.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
        canvas.drawRect(tmp, areaFill)
        canvas.drawRect(tmp, areaStroke)
        canvas.drawCircle(tmp.right, tmp.bottom, handleRadiusPx / 2f, handlePaint)
        val selected = session.selectedPointId
        session.pointPositions().forEachIndexed { index, (point, pos) ->
            val r = handleRadiusPx / 2f
            if (point.enabled) canvas.drawCircle(pos.x, pos.y, r, pointFill) else canvas.drawCircle(pos.x, pos.y, r, pointDisabled)
            if (point.id == selected) canvas.drawCircle(pos.x, pos.y, r + 4f * density, pointSelected)
            canvas.drawText((index + 1).toString(), pos.x, pos.y + LABEL_SP * density / 3f, label)
        }
        drawToolbar(canvas)
    }

    private fun drawToolbar(canvas: Canvas) {
        canvas.drawRect(barRect, barBg)
        val hasSelection = session.selectedPointId != null
        val canAdd = session.config.targetPoints.size < TriggerLimits.MAX_TARGETS
        buttons.forEach { b ->
            val enabled = when (b.command) {
                EditCommand.DELETE, EditCommand.TOGGLE -> hasSelection
                EditCommand.ADD -> canAdd
                EditCommand.TEST -> !busy
                EditCommand.CANCEL, EditCommand.DONE -> !busy
            }
            val paint = if (b.primary) buttonPrimary else buttonBg
            val alpha = paint.alpha
            if (!enabled) paint.alpha = alpha / DISABLED_DIVISOR
            val radius = CORNER_DP * density
            canvas.drawRoundRect(b.rect, radius, radius, paint)
            paint.alpha = alpha
            canvas.drawText(b.text, b.rect.centerX(), b.rect.centerY() + BUTTON_SP * density / 3f, buttonText)
        }
        val hintY = buttons.first().rect.bottom + HINT_SP * density * 1.5f
        canvas.drawText(hint, PAD_DP * density, hintY, hintText)
    }

    @SuppressLint("ClickableViewAccessibility") // an editing surface, not a clickable control
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Raw = physical display pixels; the window covers the whole display from (0,0), so this is the space
        // the session, the gameplay overlay and dispatchGesture all share.
        val x = event.rawX
        val y = event.rawY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedButton = buttons.firstOrNull { it.rect.contains(x, y) }
                if (pressedButton == null) session.onDown(x, y)
            }
            MotionEvent.ACTION_MOVE -> if (pressedButton == null) {
                session.onMove(x, y)
                if (session.grabbed != EditHandle.None) invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val button = pressedButton
                pressedButton = null
                if (button != null) {
                    if (button.rect.contains(x, y) && !busy) runCommand(button.command)
                } else {
                    when (val gesture = session.onUp(x, y)) {
                        is EditGesture.TapEmpty -> if (!barRect.contains(x, y)) session.addPointAt(gesture.xPx, gesture.yPx)
                        is EditGesture.PointSelected, is EditGesture.Dragged, EditGesture.Idle -> Unit
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedButton = null
                session.cancelGesture()
                invalidate()
            }
            else -> Unit
        }
        return true
    }

    /** Local edits are applied here; TEST / CANCEL / DONE need the runtime (injection, persistence). */
    private fun runCommand(command: EditCommand) {
        when (command) {
            EditCommand.ADD -> session.addPointAt(session.geometry.widthPx / 2f, session.geometry.heightPx / 2f)
            EditCommand.DELETE -> session.deleteSelected()
            EditCommand.TOGGLE -> session.toggleSelectedEnabled()
            EditCommand.TEST, EditCommand.CANCEL, EditCommand.DONE -> onCommand(command)
        }
    }

    private companion object {
        const val AREA_FILL_ALPHA = 50
        const val BAR_ALPHA = 200
        const val BUTTON_ALPHA = 60
        const val DISABLED_DIVISOR = 3
        const val HANDLE_DP = 22f
        const val PAD_DP = 8f
        const val BUTTON_H_DP = 40f
        const val CORNER_DP = 8f
        const val LABEL_SP = 12f
        const val BUTTON_SP = 13f
        const val HINT_SP = 11f
    }
}
