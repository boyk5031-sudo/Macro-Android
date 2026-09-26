package com.macroandroid.automation.android.trigger

import android.content.Context
import com.macroandroid.automation.android.accessibility.AccessibilityServiceRegistry
import com.macroandroid.automation.android.consent.AccessibilityConsent
import com.macroandroid.core.common.contract.TriggerAccessStatus
import com.macroandroid.core.platform.InstallSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Permission/access verification layer for Trigger Areas (§9). Nothing here is cached beyond the live flows:
 * consent (for the current disclosure version) comes from DataStore, the Settings switch from Secure settings, connection + gesture capability from
 * the bound service. If any of them flips, [status] emits and the runtime tears the overlay down.
 */
@Singleton
class TriggerAccessChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val consent: AccessibilityConsent,
    private val registry: AccessibilityServiceRegistry,
) {
    val status: Flow<TriggerAccessStatus> = combine(
        consent.consentCurrent,
        consent.serviceEnabledInSettings,
        registry.service,
    ) { granted, enabled, service ->
        TriggerAccessStatus(
            consentGranted = granted,
            serviceEnabledInSettings = enabled,
            serviceConnected = service != null,
            gestureDispatchSupported = service != null && registry.canPerformGestures,
            restrictedSettingsMayApply = InstallSource.restrictedSettingsMayApply(context),
        )
    }.distinctUntilChanged()

    /** Synchronous snapshot for the injection path (consent read from the cached flow value is not enough). */
    suspend fun current(): TriggerAccessStatus = TriggerAccessStatus(
        consentGranted = consent.isConsentCurrent(),
        serviceEnabledInSettings = AccessibilityServiceRegistry.isEnabledInSettings(context),
        serviceConnected = registry.isConnected,
        gestureDispatchSupported = registry.canPerformGestures,
        restrictedSettingsMayApply = InstallSource.restrictedSettingsMayApply(context),
    )

    fun refresh() = consent.refreshEnabledState()
}
