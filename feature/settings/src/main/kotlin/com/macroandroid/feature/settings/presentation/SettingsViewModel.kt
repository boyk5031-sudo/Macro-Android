package com.macroandroid.feature.settings.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroandroid.core.common.contract.AuditContract
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.database.repository.AuditKind
import com.macroandroid.core.database.repository.ExecutionRepository
import com.macroandroid.core.datastore.ThemeMode
import com.macroandroid.core.datastore.UserPreferences
import com.macroandroid.core.datastore.UserPreferencesRepository
import com.macroandroid.feature.settings.data.DiagnosticsExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SettingsEvent {
    data object HistoryCleared : SettingsEvent
    data object OnboardingReset : SettingsEvent
    data object DiagnosticsExported : SettingsEvent
    data class Error(val error: AppError) : SettingsEvent
}

/** FR-SET-1: every toggle writes straight to DataStore; security-relevant changes are audited (FR-SEC-2). */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: UserPreferencesRepository,
    private val executions: ExecutionRepository,
    private val audit: AuditContract,
    private val diagnostics: DiagnosticsExporter,
) : ViewModel() {
    val preferences: StateFlow<UserPreferences?> =
        prefs.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _events = MutableSharedFlow<SettingsEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { prefs.setThemeMode(mode) }
    fun setDynamicColor(v: Boolean) = viewModelScope.launch { prefs.setDynamicColor(v) }
    fun setConfirmBeforeRun(v: Boolean) = viewModelScope.launch { prefs.setConfirmBeforeRun(v) }
    fun setKeepScreenOn(v: Boolean) = viewModelScope.launch { prefs.setKeepScreenOn(v) }
    fun setHistoryRetentionDays(v: Int) = viewModelScope.launch { prefs.setHistoryRetentionDays(v) }
    fun setHistoryMaxRuns(v: Int) = viewModelScope.launch { prefs.setHistoryMaxRuns(v) }
    fun setCopyApkOnImport(v: Boolean) = viewModelScope.launch { prefs.setCopyApkOnImport(v) }

    fun setVerboseLogging(v: Boolean) = viewModelScope.launch {
        prefs.setVerboseLogging(v)
        audit.record(AuditKind.OTHER.name, "settings.verboseLogging", v.toString())
    }

    fun setIncludeSecureValuesInExport(v: Boolean) = viewModelScope.launch {
        prefs.setIncludeSecureValuesInExport(v)
        audit.record(AuditKind.OTHER.name, "settings.includeSecureValuesInExport", v.toString())
    }

    fun clearHistory() = viewModelScope.launch {
        executions.deleteAll()
        audit.record(AuditKind.DATA_WIPED.name, "executions")
        _events.emit(SettingsEvent.HistoryCleared)
    }

    fun resetOnboarding() = viewModelScope.launch {
        prefs.setOnboardingCompleted(false)
        _events.emit(SettingsEvent.OnboardingReset)
    }

    fun exportDiagnostics(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            when (val r = diagnostics.writeTo(uri, diagnostics.render())) {
                is AppResult.Ok -> {
                    audit.record(AuditKind.OTHER.name, "diagnostics.exported")
                    _events.emit(SettingsEvent.DiagnosticsExported)
                }
                is AppResult.Err -> _events.emit(SettingsEvent.Error(r.error))
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
