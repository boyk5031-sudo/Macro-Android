package com.macroandroid.automation.android.trigger

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.macroandroid.automation.trigger.TouchKind
import com.macroandroid.automation.trigger.TriggerTouch

/**
 * The gameplay overlay for ONE trigger area. It is exactly the size of the area, so touches outside never reach
 * it (they go to the game); touches inside are consumed as trigger presses and forwarded in physical pixels.
 * Visuals are minimal by design (§11): a thin outline and a small marker, or nothing at all when the user hid it.
 */
@SuppressLint("ViewConstructor") // only ever created programmatically by TriggerOverlayController
class TriggerOverlayView(
    context: Context,
    private val onTouch: (TriggerTouch) -> Unit,
) : View(context) {

    var indicatorVisible: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var label: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private var flashUntil = 0L
    private val density = context.resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(FILL_ALPHA, 0x4C, 0xAF, 0x50) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(STROKE_ALPHA, 0x4C, 0xAF, 0x50)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val flash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(FLASH_ALPHA, 0xFF, 0xFF, 0xFF) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TEXT_SP * density
        setShadowLayer(2f * density, 0f, 0f, Color.BLACK)
    }
    private val rect = RectF()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = null
    }

    /** Brief visual acknowledgement of an activation, so the user knows the press registered. */
    fun flashActivated() {
        flashUntil = System.currentTimeMillis() + FLASH_MS
        invalidate()
        postDelayed({ invalidate() }, FLASH_MS)
    }

    override fun onDraw(canvas: Canvas) {
        if (!indicatorVisible) return
        rect.set(stroke.strokeWidth, stroke.strokeWidth, width - stroke.strokeWidth, height - stroke.strokeWidth)
        val radius = CORNER_DP * density
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, stroke)
        if (System.currentTimeMillis() < flashUntil) canvas.drawRoundRect(rect, radius, radius, flash)
        if (label.isNotEmpty() && width > MIN_LABEL_WIDTH_DP * density) {
            canvas.drawText(label, PADDING_DP * density, (PADDING_DP + TEXT_SP) * density, text)
        }
    }

    @SuppressLint("ClickableViewAccessibility") // deliberately not a clickable control; it is a game input zone
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val kind = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchKind.DOWN
            MotionEvent.ACTION_UP -> TouchKind.UP
            MotionEvent.ACTION_CANCEL -> TouchKind.CANCEL
            // Extra fingers are not separate presses; ACTION_POINTER_DOWN is ignored on purpose.
            else -> return true
        }
        onTouch(TriggerTouch(kind, event.rawX, event.rawY, event.eventTime))
        return true
    }

    private companion object {
        const val FILL_ALPHA = 40
        const val STROKE_ALPHA = 200
        const val FLASH_ALPHA = 90
        const val FLASH_MS = 120L
        const val CORNER_DP = 8f
        const val PADDING_DP = 6f
        const val TEXT_SP = 12f
        const val MIN_LABEL_WIDTH_DP = 72f
    }
}
