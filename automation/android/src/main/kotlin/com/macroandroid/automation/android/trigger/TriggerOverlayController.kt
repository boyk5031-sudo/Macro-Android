package com.macroandroid.automation.android.trigger

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.core.content.getSystemService
import com.macroandroid.automation.trigger.PointerEvent
import com.macroandroid.automation.trigger.TriggerId
import com.macroandroid.core.common.display.DisplayGeometry
import com.macroandroid.core.common.display.DisplayRect
import com.macroandroid.core.common.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds/removes `TYPE_ACCESSIBILITY_OVERLAY` windows through the bound service's WindowManager. This window type
 * is granted to accessibility services by the system – no `SYSTEM_ALERT_WINDOW` (which the build forbids).
 * Main-thread only. One window per visible trigger; `hideAll` is idempotent so teardown can never leak a window.
 *
 * Window/input design (layer A vs C separation):
 *  * a gameplay window is exactly the trigger area – never full-screen – so the system routes to it only pointers
 *    that go DOWN inside the area; everything else goes straight to the game;
 *  * `FLAG_SPLIT_TOUCH` is set explicitly. Raw `WindowManager.addView` windows do not get it automatically (only
 *    activity windows do, via PhoneWindow), and without it the dispatcher does not split a new finger to this
 *    window while the game already holds one – the trigger could not be pressed during a joystick drag at all;
 *  * `FLAG_NOT_TOUCH_MODAL` + `FLAG_NOT_FOCUSABLE`: no outside-touch capture, no key focus stolen from the game;
 *  * the debug HUD is a separate `FLAG_NOT_TOUCHABLE` window: it renders, it is never an input target.
 */
@Singleton
class TriggerOverlayController @Inject constructor(private val logger: Logger) {

    private class Entry(val view: TriggerOverlayView, val params: WindowManager.LayoutParams, var bounds: DisplayRect)

    private class EditorEntry(val view: TriggerEditOverlayView, val params: WindowManager.LayoutParams)

    private class HudEntry(val view: TriggerDebugHudView, val params: WindowManager.LayoutParams)

    private val entries = LinkedHashMap<TriggerId, Entry>()
    private var editor: EditorEntry? = null
    private var hud: HudEntry? = null
    private var windowManager: WindowManager? = null

    val visibleIds: Set<TriggerId> get() = entries.keys.toSet()
    val editorView: TriggerEditOverlayView? get() = editor?.view
    val debugHudVisible: Boolean get() = hud != null

    /** Shows or repositions the overlay for [id]. Returns false when the system refused the window. */
    fun show(
        service: AccessibilityService,
        id: TriggerId,
        bounds: DisplayRect,
        label: String,
        indicatorVisible: Boolean,
        onPointerEvent: (PointerEvent) -> Unit,
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
        val view = TriggerOverlayView(service, onPointerEvent).apply {
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

    /**
     * While targets are being injected the window must not swallow the injected contacts: a target that lies
     * inside its own trigger area would otherwise tap the overlay instead of the game.
     */
    fun setTouchable(id: TriggerId, touchable: Boolean) {
        val entry = entries[id] ?: return
        if (entry.params.setNotTouchable(!touchable)) {
            runCatching { windowManager?.updateViewLayout(entry.view, entry.params) }
                .onFailure { logger.w(TAG, "updateViewLayout(touchable=$touchable) failed", it) }
        }
    }

    /** Shows the full-display edit-mode window; hides any gameplay overlays first so the two never overlap. */
    fun showEditor(service: AccessibilityService, view: TriggerEditOverlayView, geometry: DisplayGeometry): Boolean {
        val wm = windowManager ?: service.getSystemService<WindowManager>()?.also { windowManager = it } ?: return false
        hideEditor()
        entries.keys.toList().forEach(::hide)
        val params = newParams().applyBounds(DisplayRect(0, 0, geometry.widthPx, geometry.heightPx))
        return try {
            wm.addView(view, params)
            editor = EditorEntry(view, params)
            true
        } catch (e: WindowManager.BadTokenException) {
            logger.w(TAG, "editor addView refused", e)
            false
        } catch (e: IllegalStateException) {
            logger.w(TAG, "editor addView failed", e)
            false
        }
    }

    fun setEditorTouchable(touchable: Boolean) {
        val entry = editor ?: return
        if (entry.params.setNotTouchable(!touchable)) {
            runCatching { windowManager?.updateViewLayout(entry.view, entry.params) }
                .onFailure { logger.w(TAG, "editor updateViewLayout failed", it) }
        }
    }

    fun hideEditor() {
        val entry = editor ?: return
        editor = null
        runCatching { windowManager?.removeViewImmediate(entry.view) }
            .onFailure { logger.w(TAG, "editor removeView failed", it) }
    }

    /** Debug HUD: top-start corner, untouchable, sized to its text. Idempotent. */
    fun showDebugHud(service: AccessibilityService, geometry: DisplayGeometry): Boolean {
        if (hud != null) return true
        val wm = windowManager ?: service.getSystemService<WindowManager>()?.also { windowManager = it } ?: return false
        val view = TriggerDebugHudView(service)
        val params = newParams().apply {
            x = HUD_MARGIN_PX
            y = geometry.heightPx / HUD_TOP_DIVISOR
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return try {
            wm.addView(view, params)
            hud = HudEntry(view, params)
            true
        } catch (e: WindowManager.BadTokenException) {
            logger.w(TAG, "hud addView refused", e)
            false
        } catch (e: IllegalStateException) {
            logger.w(TAG, "hud addView failed", e)
            false
        }
    }

    fun updateDebugHud(lines: List<String>) {
        hud?.view?.lines = lines
    }

    fun hideDebugHud() {
        val entry = hud ?: return
        hud = null
        runCatching { windowManager?.removeViewImmediate(entry.view) }
            .onFailure { logger.w(TAG, "hud removeView failed", it) }
    }

    /** Returns true when the flag actually changed. */
    private fun WindowManager.LayoutParams.setNotTouchable(notTouchable: Boolean): Boolean {
        val before = flags
        flags = if (notTouchable) {
            flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        return flags != before
    }

    fun hide(id: TriggerId) {
        val entry = entries.remove(id) ?: return
        runCatching { windowManager?.removeViewImmediate(entry.view) }
            .onFailure { logger.w(TAG, "removeView failed", it) }
    }

    fun hideAll() {
        entries.keys.toList().forEach(::hide)
        hideEditor()
        hideDebugHud()
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
            WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
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
        const val HUD_MARGIN_PX = 16
        const val HUD_TOP_DIVISOR = 8
    }
}
