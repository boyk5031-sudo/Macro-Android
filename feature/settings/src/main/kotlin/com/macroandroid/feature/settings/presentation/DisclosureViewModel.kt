package com.macroandroid.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroandroid.core.common.contract.ConsentContract
import com.macroandroid.feature.settings.data.CapabilityStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DisclosureUiState(val consentGranted: Boolean = false, val serviceEnabled: Boolean = false)

/** FR-PRM-2: prominent disclosure; the settings button is enabled only after affirmative consent. */
@HiltViewModel
class DisclosureViewModel @Inject constructor(
    private val consent: ConsentContract,
    private val capabilities: CapabilityStatus,
) : ViewModel() {
    val uiState: StateFlow<DisclosureUiState> = combine(consent.consentGranted, consent.serviceEnabledInSettings) { c, e ->
        DisclosureUiState(c, e)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DisclosureUiState())

    fun agree() = viewModelScope.launch { consent.grantConsent() }
    fun openAccessibilitySettings() = capabilities.openAccessibilitySettings()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
