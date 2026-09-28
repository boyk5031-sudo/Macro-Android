package com.macroandroid.automation.android.trigger

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * Debug mode read-out (§ Debugging). Lives in its own `FLAG_NOT_TOUCHABLE` window, so it can never become an
 * input target: it shows pointer ids / indices / coordinates / actions of the trigger windows' own streams and the
 * trigger state, and nothing else. It cannot show the game's pointers – those never enter this process, which is
 * precisely why the game's gesture is unaffected by the trigger (use Developer options ▸ "Pointer location" to
 * watch the system-wide streams side by side).
 */
@SuppressLint("ViewConstructor") // only ever created programmatically by TriggerOverlayController
class TriggerDebugHudView(context: Context) : View(context) {

    var lines: List<String> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            requestLayout() // WRAP_CONTENT window: grows and shrinks with the text
            invalidate()
        }

    private val density = context.resources.displayMetrics.density
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(BG_ALPHA, 0, 0, 0) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TEXT_SP * density
        typeface = Typeface.MONOSPACE
    }
    private val rect = RectF()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val pad: Float get() = PAD_DP * density
    private val lineHeight: Float get() = text.textSize * LINE_SPACING
    private val contentWidth: Float get() = (lines.maxOfOrNull { text.measureText(it) } ?: 0f) + 2 * pad
    private val contentHeight: Float get() = lines.size * lineHeight + 2 * pad

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(contentWidth.toInt() + 1, widthMeasureSpec),
            resolveSize(contentHeight.toInt() + 1, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (lines.isEmpty()) return
        val inset = pad
        val step = lineHeight
        rect.set(0f, 0f, contentWidth, contentHeight)
        canvas.drawRoundRect(rect, CORNER_DP * density, CORNER_DP * density, background)
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, inset, inset + (i + 1) * step - text.descent(), text)
        }
    }

    private companion object {
        const val BG_ALPHA = 170
        const val TEXT_SP = 11f
        const val PAD_DP = 8f
        const val CORNER_DP = 6f
        const val LINE_SPACING = 1.25f
    }
}
