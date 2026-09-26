package com.macroandroid.automation.android.accessibility

import android.accessibilityservice.AccessibilityService
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

    val isConnected: Boolean get() = _service.value != null

    internal fun attach(service: AccessibilityService) = _service.update { service }

    internal fun detach(service: AccessibilityService) = _service.update { if (it === service) null else it }

    internal fun onEvent(event: AccessibilityEvent) {
        if (!automationActive.value) return
        _lastEvent.value = WindowEvent(
            type = event.eventType,
            packageName = event.packageName?.toString(),
            at = System.nanoTime(),
        )
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
