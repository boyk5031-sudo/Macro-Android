package com.macroandroid.automation.android.trigger

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.core.content.getSystemService
import com.macroandroid.automation.trigger.TriggerId
import com.macroandroid.automation.trigger.TriggerTouch
import com.macroandroid.core.common.display.DisplayRect
import com.macroandroid.core.common.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds/removes `TYPE_ACCESSIBILITY_OVERLAY` windows through the bound service's WindowManager. This window type
 * is granted to accessibility services by the system – no `SYSTEM_ALERT_WINDOW` (which the build forbids).
 * Main-thread only. One window per visible trigger; `hideAll` is idempotent so teardown can never leak a window.
 */
@Singleton
class TriggerOverlayController @Inject constructor(private val logger: Logger) {

    private class Entry(val view: TriggerOverlayView, val params: WindowManager.LayoutParams, var bounds: DisplayRect)

    private val entries = LinkedHashMap<TriggerId, Entry>()
    private var windowManager: WindowManager? = null

    val visibleIds: Set<TriggerId> get() = entries.keys.toSet()

    /** Shows or repositions the overlay for [id]. Returns false when the system refused the window. */
    fun show(
        service: AccessibilityService,
        id: TriggerId,
        bounds: DisplayRect,
        label: String,
        indicatorVisible: Boolean,
        onTouch: (TriggerTouch) -> Unit,
    ): Boolean {
        val wm = windowManager ?: service.getSystemService<WindowManager>()?.also { windowManager = it } ?: return false
        val existing = entries[id]
        if (existing != null) {
            existing.view.label = label
            existing.view.indicatorVisible = indicatorVisible
            if (existing.bounds != bounds) {
                existing.params.applyBounds(bounds)
                existing.bounds = bounds
                return runCatching { wm.updateViewLayout(existing.view, existing.params) }
                    .onFailure { logger.w(TAG, "updateViewLayout failed", it) }
                    .isSuccess
            }
            return true
        }
        val view = TriggerOverlayView(service, onTouch).apply {
            this.label = label
            this.indicatorVisible = indicatorVisible
        }
        val params = newParams().applyBounds(bounds)
        return try {
            wm.addView(view, params)
            entries[id] = Entry(view, params, bounds)
            true
        } catch (e: WindowManager.BadTokenException) {
            logger.w(TAG, "addView refused", e)
            false
        } catch (e: IllegalStateException) {
            logger.w(TAG, "addView failed", e)
            false
        }
    }

    fun flash(id: TriggerId) {
        entries[id]?.view?.flashActivated()
    }

    fun hide(id: TriggerId) {
        val entry = entries.remove(id) ?: return
        runCatching { windowManager?.removeViewImmediate(entry.view) }
            .onFailure { logger.w(TAG, "removeView failed", it) }
    }

    fun hideAll() {
        entries.keys.toList().forEach(::hide)
        windowManager = null
    }

    private fun WindowManager.LayoutParams.applyBounds(bounds: DisplayRect): WindowManager.LayoutParams {
        x = bounds.left
        y = bounds.top
        width = bounds.width.coerceAtLeast(1)
        height = bounds.height.coerceAtLeast(1)
        return this
    }

    private fun newParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        1,
        1,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private companion object {
        const val TAG = "TriggerOverlay"
    }
}
