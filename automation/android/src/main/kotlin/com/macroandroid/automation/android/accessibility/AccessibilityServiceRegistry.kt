package com.macroandroid.automation.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide handle to the bound [MacroAccessibilityService]. The service registers itself in
 * `onServiceConnected` and unregisters in `onUnbind`/`onDestroy`; everything else (gateway, gates,
 * permission center) reads this instead of holding a service reference.
 */
@Singleton
class AccessibilityServiceRegistry @Inject constructor() {
    private val _service = MutableStateFlow<AccessibilityService?>(null)
    val service: StateFlow<AccessibilityService?> = _service.asStateFlow()

    private val _lastEvent = MutableStateFlow<WindowEvent?>(null)

    /** Latest window-state/content event; used to wake node polling early. */
    val lastEvent: StateFlow<WindowEvent?> = _lastEvent.asStateFlow()

    /** Whether the engine is currently inside a UI segment; when false the service ignores events cheaply. */
    val automationActive = MutableStateFlow(false)

    private val _foregroundPackage = MutableStateFlow<String?>(null)

    /**
     * Package of the window that currently has input focus (from `TYPE_WINDOW_STATE_CHANGED`). Only the package
     * name is kept, in memory, so the Trigger Area runtime can bind configurations to a game (§13).
     */
    val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()

    private val _displayChanges = MutableStateFlow(0L)

    /** Incremented on every service configuration change (rotation, fold, density); consumers re-measure. */
    val displayChanges: StateFlow<Long> = _displayChanges.asStateFlow()

    val isConnected: Boolean get() = _service.value != null

    /** True when the bound service may call `dispatchGesture` (declared `canPerformGestures` + granted). */
    val canPerformGestures: Boolean
        get() {
            val info = _service.value?.serviceInfo ?: return false
            return info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0
        }

    internal fun attach(service: AccessibilityService) = _service.update { service }

    internal fun detach(service: AccessibilityService) {
        _service.update { if (it === service) null else it }
        _foregroundPackage.value = null
    }

    internal fun onDisplayChanged() = _displayChanges.update { it + 1 }

    internal fun onEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) trackForeground(event)
        if (!automationActive.value) return
        _lastEvent.value = WindowEvent(
            type = event.eventType,
            packageName = event.packageName?.toString(),
            at = System.nanoTime(),
        )
    }

    /**
     * The focused window's package is authoritative: our own accessibility overlay is not focusable, so adding it
     * never flips the foreground to us (which would otherwise hide the overlay again in a loop).
     */
    private fun trackForeground(event: AccessibilityEvent) {
        val service = _service.value ?: return
        val focused = runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()
        val fromEvent = event.packageName?.toString()?.takeIf { it != service.packageName || focused == it }
        val next = focused ?: fromEvent ?: return
        _foregroundPackage.value = next
    }

    data class WindowEvent(val type: Int, val packageName: String?, val at: Long)

    companion object {
        /** True when our service component is listed in Secure settings, even if not (yet) bound. */
        fun isEnabledInSettings(context: Context): Boolean {
            val expected = ComponentName(context, MacroAccessibilityService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
