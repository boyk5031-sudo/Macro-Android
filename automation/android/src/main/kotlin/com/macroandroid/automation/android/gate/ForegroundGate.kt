package com.macroandroid.automation.android.gate

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Whether the app may start activities / a foreground service right now (Android 10+/14+ BAL rules):
 * app visible, or the user tapped one of our notifications within the last 10 s.
 */
@Singleton
class ForegroundGate @Inject constructor() {
    private val lastNotificationTapNanos = AtomicLong(0)

    val isAppVisible: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    fun recordNotificationTap() = lastNotificationTapNanos.set(System.nanoTime())

    fun mayStartActivity(): Boolean {
        if (isAppVisible) return true
        val age = System.nanoTime() - lastNotificationTapNanos.get()
        return lastNotificationTapNanos.get() != 0L && age < TAP_WINDOW.inWholeNanoseconds
    }

    private companion object {
        val TAP_WINDOW = 10.seconds
    }
}
