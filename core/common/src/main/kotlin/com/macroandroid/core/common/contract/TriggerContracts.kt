package com.macroandroid.core.common.contract

import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import kotlinx.coroutines.flow.Flow

/** One access requirement of the Trigger Area feature; each maps to a system screen the user must visit. */
enum class TriggerRequirement {
    /** In-app consent for the accessibility service (Play policy disclosure). */
    ACCESSIBILITY_CONSENT,

    /** Service switched on in Android Settings → Accessibility. */
    ACCESSIBILITY_SERVICE_ENABLED,

    /** Service bound by the system (can lag behind the switch, or be killed). */
    ACCESSIBILITY_SERVICE_CONNECTED,

    /** Bound service reports gesture dispatch (`canPerformGestures`) – required for touch injection. */
    GESTURE_DISPATCH,
}

/** Fresh snapshot of the access checks; never cached across process death. */
data class TriggerAccessStatus(
    val consentGranted: Boolean,
    val serviceEnabledInSettings: Boolean,
    val serviceConnected: Boolean,
    val gestureDispatchSupported: Boolean,
    /** Android 13+ "restricted setting" may block the accessibility switch for side-loaded builds. */
    val restrictedSettingsMayApply: Boolean,
) {
    val missing: List<TriggerRequirement>
        get() = buildList {
            if (!consentGranted) add(TriggerRequirement.ACCESSIBILITY_CONSENT)
            if (!serviceEnabledInSettings) add(TriggerRequirement.ACCESSIBILITY_SERVICE_ENABLED)
            if (!serviceConnected) add(TriggerRequirement.ACCESSIBILITY_SERVICE_CONNECTED)
            if (!gestureDispatchSupported) add(TriggerRequirement.GESTURE_DISPATCH)
        }
    val ready: Boolean get() = missing.isEmpty()
}

/** Live state of the trigger runtime for the list screen and diagnostics. */
data class TriggerRuntimeStatus(
    /** Ids of configurations whose overlay is currently shown. */
    val visibleTriggerIds: Set<String> = emptySet(),
    /** Id manually activated by the user (configurations not bound to an app). */
    val manuallyArmedId: String? = null,
    val foregroundPackage: String? = null,
    /** Why nothing is shown although a configuration is armed; null when visible or idle. */
    val blockedReason: ErrorCode? = null,
    val lastActivationAtMillis: Long? = null,
    val activationCount: Long = 0,
    val executing: Boolean = false,
    /** Id being edited on screen (full-display edit overlay), or null. */
    val editingTriggerId: String? = null,
)

/**
 * Trigger Area runtime (bound in automation:android). Features call it to arm/test configurations and to render
 * the access checklist; the runtime itself shows/hides the accessibility overlay and injects the configured taps.
 */
interface TriggerRuntimeContract {
    val status: Flow<TriggerRuntimeStatus>
    val access: Flow<TriggerAccessStatus>

    /** Re-reads system settings (call from `onResume` after returning from Android Settings). */
    fun refreshAccess()

    /** Arms a configuration that is not bound to a package; only one can be armed at a time. */
    suspend fun arm(triggerId: String): AppResult<Unit>
    suspend fun disarm()

    /**
     * Executes the target actions of the given configuration (its JSON form, so unsaved editor state can be
     * tested) once, without the overlay. Uses the exact same resolver and injection path as a real activation.
     */
    suspend fun test(configurationJson: String): AppResult<Unit>

    /** Taps ONE target point of the given configuration once (no reaction delay, no repeats). */
    suspend fun testTarget(configurationJson: String, pointId: String): AppResult<Unit>

    /**
     * Enters on-screen edit mode for a saved configuration: the runtime shows a full-display editor over the
     * bound app (or over any other app for unbound configurations) until the user taps Done or Cancel, or
     * [stopOverlayEdit] is called. Done writes the edited area/points back to the repository.
     */
    suspend fun startOverlayEdit(triggerId: String): AppResult<Unit>
    suspend fun stopOverlayEdit()
}
