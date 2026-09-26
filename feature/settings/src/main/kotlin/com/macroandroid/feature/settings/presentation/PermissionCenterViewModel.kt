package com.macroandroid.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroandroid.core.common.contract.ConsentContract
import com.macroandroid.core.database.repository.MacroRepository
import com.macroandroid.core.datastore.CURRENT_A11Y_DISCLOSURE_VERSION
import com.macroandroid.core.datastore.UserPreferencesRepository
import com.macroandroid.feature.settings.data.CapabilityStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PermissionCenterUiState(
    val notificationsEnabled: Boolean = false,
    val notificationsNeedRuntimePermission: Boolean = false,
    val consentGranted: Boolean = false,
    val consentOutdated: Boolean = false,
    val serviceEnabledInSettings: Boolean = false,
    val serviceConnected: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val pinShortcutsSupported: Boolean = false,
    val restrictedSettingsMayApply: Boolean = false,
    val a11yMacroCount: Int = 0,
    val loaded: Boolean = false,
)

/** FR-PRM-1..4. Device state is polled on resume (the system settings screens give no callback). */
@HiltViewModel
class PermissionCenterViewModel @Inject constructor(
    private val capabilities: CapabilityStatus,
    private val consent: ConsentContract,
    private val prefs: UserPreferencesRepository,
    private val macros: MacroRepository,
) : ViewModel() {
    private data class DeviceState(
        val notifications: Boolean,
        val battery: Boolean,
        val pin: Boolean,
        val restricted: Boolean,
    )

    private val device = MutableStateFlow(readDevice())

    val uiState: StateFlow<PermissionCenterUiState> = combine(
        device,
        consent.consentGranted,
        consent.serviceEnabledInSettings,
        consent.serviceConnected,
        combine(prefs.preferences, macros.observeSummaries()) { p, s ->
            p.accessibilityDisclosureVersion to s.count { it.requiresAccessibility }
        },
    ) { d, granted, enabled, connected, (version, count) ->
        PermissionCenterUiState(
            notificationsEnabled = d.notifications,
            notificationsNeedRuntimePermission = capabilities.notificationsNeedRuntimePermission,
            consentGranted = granted,
            consentOutdated = granted && version < CURRENT_A11Y_DISCLOSURE_VERSION,
            serviceEnabledInSettings = enabled,
            serviceConnected = connected,
            batteryUnrestricted = d.battery,
            pinShortcutsSupported = d.pin,
            restrictedSettingsMayApply = d.restricted,
            a11yMacroCount = count,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PermissionCenterUiState())

    /** Call from ON_RESUME so returning from system settings refreshes the rows. */
    fun refresh() {
        device.value = readDevice()
    }

    private fun readDevice() = DeviceState(
        notifications = capabilities.notificationsEnabled,
        battery = capabilities.ignoringBatteryOptimizations,
        pin = capabilities.pinShortcutsSupported,
        restricted = capabilities.restrictedSettingsMayApply,
    )

    fun openNotificationSettings() = capabilities.openNotificationSettings()
    fun openAccessibilitySettings() = capabilities.openAccessibilitySettings()
    fun openBatteryList() = capabilities.openBatteryOptimizationList()
    fun openAppInfo() = capabilities.openAppInfo()

    fun markNotificationRationaleShown() = viewModelScope.launch { prefs.setNotificationsRationaleShown(true) }

    /** FR-PRM-2 withdrawal: consent is revoked and every macro with accessibility steps is disabled. */
    fun withdrawConsent() = viewModelScope.launch {
        consent.revokeConsent()
        macros.observeSummaries().first()
            .filter { it.requiresAccessibility && it.enabled }
            .forEach { macros.setEnabled(it.id, false) }
    }

    val notificationsRationaleShown = prefs.preferences.map { it.notificationsRationaleShown }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
