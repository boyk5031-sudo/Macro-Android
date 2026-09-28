package com.macroandroid.automation.android.trigger

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import com.macroandroid.automation.trigger.PointerAction
import com.macroandroid.automation.trigger.PointerEvent
import com.macroandroid.automation.trigger.PointerSample

/**
 * The gameplay overlay for ONE trigger area (layer A: visuals + raw event translation, nothing else).
 *
 * The window is exactly the size of the area and has `FLAG_SPLIT_TOUCH`, so the system routes to it only the
 * pointers that go DOWN inside the area; a finger that is already down on the game keeps its own stream in the
 * game's window and is never seen, moved or cancelled by this view. Every event this window does receive is
 * translated into a [PointerEvent] with stable pointer ids (`getPointerId`) – never bare indices – and handed
 * to [PointerTracker] via [onPointerEvent]. No decision is made here.
 *
 * Visuals are minimal by design (§11): a thin outline and a small marker, or nothing at all when the user hid it.
 */
@SuppressLint("ViewConstructor") // only ever created programmatically by TriggerOverlayController
class TriggerOverlayView(
    context: Context,
    private val onPointerEvent: (PointerEvent) -> Unit,
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
        onPointerEvent(event.toPointerEvent())
        // The pointers in this stream started inside the area: they belong to the trigger. Returning false would
        // not hand them to the game (cross-process windows never forward), it would only stop the stream.
        return true
    }

    private fun MotionEvent.toPointerEvent(): PointerEvent {
        val action = when (actionMasked) {
            MotionEvent.ACTION_DOWN -> PointerAction.DOWN
            MotionEvent.ACTION_POINTER_DOWN -> PointerAction.POINTER_DOWN
            MotionEvent.ACTION_MOVE -> PointerAction.MOVE
            MotionEvent.ACTION_POINTER_UP -> PointerAction.POINTER_UP
            MotionEvent.ACTION_UP -> PointerAction.UP
            else -> PointerAction.CANCEL // ACTION_CANCEL and anything unexpected end the press safely
        }
        // Raw coordinates are physical display pixels: the window sits at the area's origin with LAYOUT_IN_SCREEN,
        // which is the space the tracker, the controller and dispatchGesture share.
        val samples = List(pointerCount) { index ->
            val id = getPointerId(index)
            val resolved = findPointerIndex(id) // == index by construction; kept explicit so id/index never mix
            PointerSample(id = id, index = resolved, xPx = rawXAt(resolved), yPx = rawYAt(resolved))
        }
        return PointerEvent(action = action, actionIndex = actionIndex, pointers = samples, atMs = eventTime)
    }

    /** Per-pointer raw coordinates exist from API 29; before that every pointer shares the window's offset. */
    private fun MotionEvent.rawXAt(index: Int): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getRawX(index) else getX(index) + (rawX - x)

    private fun MotionEvent.rawYAt(index: Int): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getRawY(index) else getY(index) + (rawY - y)

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
