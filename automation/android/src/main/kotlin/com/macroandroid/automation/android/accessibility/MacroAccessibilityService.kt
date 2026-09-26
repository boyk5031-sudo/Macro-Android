package com.macroandroid.automation.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.macroandroid.core.common.logging.Logger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Thin accessibility service: it only registers itself with [AccessibilityServiceRegistry] and forwards
 * window events. All automation logic lives in [AndroidAccessibilityGateway] and runs only while the
 * engine executes a UI segment of a user-started macro. No events are stored, no text is logged.
 *
 * Play policy: not an accessibility tool; disclosure + consent handled by the app before the user is
 * sent to system settings (ADR-0008).
 */
class MacroAccessibilityService : AccessibilityService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Deps {
        fun registry(): AccessibilityServiceRegistry
        fun logger(): Logger
        fun listener(): AccessibilityServiceListener
    }

    private val deps: Deps by lazy { EntryPointAccessors.fromApplication(applicationContext, Deps::class.java) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        deps.registry().attach(this)
        deps.logger().i(TAG, "connected")
        deps.listener().onConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        deps.registry().onEvent(event)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        deps.registry().detach(this)
        deps.logger().i(TAG, "unbound")
        deps.listener().onDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        deps.registry().detach(this)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "A11yService"
    }
}

/** Notified on bind/unbind so the app can audit and refresh the permission center. */
interface AccessibilityServiceListener {
    fun onConnected()
    fun onDisconnected()
}
