package com.macroandroid.feature.apps.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroandroid.core.common.contract.AuditContract
import com.macroandroid.core.common.contract.MacroUsageContract
import com.macroandroid.core.common.contract.ShortcutsContract
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.feature.apps.data.AppsRepository
import com.macroandroid.feature.apps.domain.InstalledApp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AppDetailUiState {
    data object Loading : AppDetailUiState
    data object NotFound : AppDetailUiState
    data class Ready(val app: InstalledApp, val macroCount: Int, val pinSupported: Boolean) : AppDetailUiState
}

@HiltViewModel
class AppDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: AppsRepository,
    private val shortcuts: ShortcutsContract,
    private val audit: AuditContract,
    macroUsage: MacroUsageContract,
) : ViewModel() {
    val packageName: String = checkNotNull(savedState[ARG_PACKAGE]) { "packageName argument missing" }

    private val _events = MutableSharedFlow<AppsUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<AppsUiEvent> = _events.asSharedFlow()

    val uiState: StateFlow<AppDetailUiState> = combine(
        repository.observeApps().map { list -> list.firstOrNull { it.packageName == packageName && !it.isUninstalled } },
        macroUsage.observeMacroCountForPackage(packageName),
    ) { app, count ->
        if (app == null) AppDetailUiState.NotFound else AppDetailUiState.Ready(app, count, shortcuts.isPinSupported)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AppDetailUiState.Loading)

    fun toggleFavorite(app: InstalledApp) = viewModelScope.launch { repository.setFavorite(app.packageName, !app.isFavorite) }

    fun pin(app: InstalledApp) = viewModelScope.launch {
        when (val r = shortcuts.pinApp(app.packageName, app.label)) {
            is AppResult.Ok -> _events.emit(AppsUiEvent.Message(app.label))
            is AppResult.Err -> _events.emit(AppsUiEvent.Error(r.error))
        }
    }

    fun launchIntent() = repository.launchIntent(packageName)

    fun onLaunchResult(app: InstalledApp, success: Boolean) = viewModelScope.launch {
        if (success) {
            _events.emit(AppsUiEvent.Launched(app.label))
        } else {
            audit.record("APP_LAUNCH_FAILED", app.packageName)
            _events.emit(AppsUiEvent.Error(AppError(ErrorCode.APP_LAUNCH_FAILED, app.packageName)))
        }
    }

    suspend fun icon(sizePx: Int) = repository.icon(packageName, sizePx)

    companion object {
        const val ARG_PACKAGE = "packageName"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
