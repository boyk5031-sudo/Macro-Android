package com.macroandroid.automation.android.trigger

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.macroandroid.automation.android.accessibility.AccessibilityServiceRegistry
import com.macroandroid.automation.trigger.Contact
import com.macroandroid.automation.trigger.InjectionCapability
import com.macroandroid.automation.trigger.InputInjectionAdapter
import com.macroandroid.core.common.coroutines.AppDispatchers
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.common.logging.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The only production [InputInjectionAdapter]: `AccessibilityService.dispatchGesture` (API 24+, no root, no
 * `SYSTEM_ALERT_WINDOW`). All contacts of one call are strokes of ONE `GestureDescription`, which Android
 * dispatches as genuine simultaneous pointers – that is what makes [ExecutionMode.MULTI_TOUCH] real rather than
 * fast sequential taps.
 *
 * Limits (documented in docs/phase-12-trigger-areas.md §9 and §12): at most `GestureDescription.getMaxStrokeCount()`
 * strokes, and – by platform design, inside system_server's `MotionEventInjector` – (a) starting an injected
 * gesture sends ACTION_CANCEL to any real touch gesture in progress, and (b) any real touch event that arrives
 * while an injected gesture is running cancels the injection (`onCancelled`). Injected contacts therefore cannot
 * coexist with a finger the user already has on the game; [InjectionCapability.coexistsWithUserTouch] is false.
 * The system lifts every stroke it started when it cancels, so no pointer can stay stuck.
 */
@Singleton
class AccessibilityInputInjectionAdapter @Inject constructor(
    private val registry: AccessibilityServiceRegistry,
    private val dispatchers: AppDispatchers,
    private val logger: Logger,
) : InputInjectionAdapter {

    override fun capability(): InjectionCapability {
        if (!registry.isConnected) return InjectionCapability(false, 0, ErrorCode.A11Y_SERVICE_DISCONNECTED)
        if (!registry.canPerformGestures) return InjectionCapability(false, 0, ErrorCode.GESTURE_DISPATCH_UNAVAILABLE)
        return InjectionCapability(
            available = true,
            maxSimultaneousContacts = GestureDescription.getMaxStrokeCount().coerceAtLeast(1),
            reason = null,
            coexistsWithUserTouch = false,
        )
    }

    override suspend fun inject(contacts: List<Contact>): AppResult<Unit> {
        if (contacts.isEmpty()) return AppResult.ok(Unit)
        val service = registry.service.value ?: return AppResult.err(ErrorCode.A11Y_SERVICE_DISCONNECTED)
        if (!registry.canPerformGestures) return AppResult.err(ErrorCode.GESTURE_DISPATCH_UNAVAILABLE)
        if (contacts.size > GestureDescription.getMaxStrokeCount()) {
            return AppResult.err(ErrorCode.LIMIT_EXCEEDED, detail = "strokes")
        }
        val gesture = buildGesture(contacts)
        val longest = contacts.maxOf { it.holdMs }
        return withContext(dispatchers.main) {
            withTimeoutOrNull(longest + COMPLETION_GRACE_MS) { dispatch(service, gesture) }
                ?: AppResult.err(ErrorCode.GESTURE_DISPATCH_FAILED, detail = "timeout")
        }
    }

    override suspend fun cancelAll() {
        // dispatchGesture has no cancel API; an in-flight gesture ends by itself within its hold time. Dispatching
        // a zero-length replacement would itself be an injected touch, so we deliberately do nothing here.
        logger.d(TAG, "cancelAll: nothing in flight can be cut short; strokes end within their hold time")
    }

    private fun buildGesture(contacts: List<Contact>): GestureDescription {
        val builder = GestureDescription.Builder()
        contacts.forEach { c ->
            val path = Path().apply { moveTo(c.x, c.y) }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0L, c.holdMs.coerceAtLeast(1L)))
        }
        return builder.build()
    }

    private suspend fun dispatch(service: AccessibilityService, gesture: GestureDescription): AppResult<Unit> =
        suspendCancellableCoroutine { cont ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(AppResult.ok(Unit))
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    // Almost always: a real touch (e.g. the user's joystick finger moving) arrived during the
                    // injection. Platform behaviour of dispatchGesture, not a fault of the plan.
                    logger.d(TAG, "gesture cancelled by the system (real touch during injection)")
                    if (cont.isActive) cont.resume(AppResult.err(ErrorCode.GESTURE_CANCELLED))
                }
            }
            val accepted = try {
                service.dispatchGesture(gesture, callback, null)
            } catch (e: IllegalStateException) {
                logger.w(TAG, "dispatchGesture threw", e)
                false
            } catch (e: SecurityException) {
                logger.w(TAG, "dispatchGesture denied", e)
                false
            }
            if (!accepted && cont.isActive) cont.resume(AppResult.err(ErrorCode.GESTURE_DISPATCH_FAILED))
        }

    private companion object {
        const val TAG = "TriggerInject"
        const val COMPLETION_GRACE_MS = 2_000L
    }
}
