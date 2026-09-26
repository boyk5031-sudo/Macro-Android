package com.macroandroid.feature.trigger.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroandroid.automation.trigger.TriggerConfiguration
import com.macroandroid.automation.trigger.TriggerId
import com.macroandroid.automation.trigger.TriggerJson
import com.macroandroid.core.common.contract.TriggerAccessStatus
import com.macroandroid.core.common.contract.TriggerRuntimeContract
import com.macroandroid.core.common.contract.TriggerRuntimeStatus
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.database.repository.StoredTrigger
import com.macroandroid.core.database.repository.TriggerRepository
import com.macroandroid.core.datastore.UserPreferencesRepository
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

data class TriggerListUiState(
    val triggers: List<StoredTrigger> = emptyList(),
    val runtime: TriggerRuntimeStatus = TriggerRuntimeStatus(),
    val access: TriggerAccessStatus? = null,
    val indicatorVisible: Boolean = true,
    val loaded: Boolean = false,
)

sealed interface TriggerListEvent {
    data class Error(val error: AppError) : TriggerListEvent
    data class Exported(val json: String, val count: Int) : TriggerListEvent
    data class Imported(val count: Int) : TriggerListEvent
    data class Duplicated(val id: TriggerId) : TriggerListEvent
}

@HiltViewModel
class TriggerListViewModel @Inject constructor(
    private val repository: TriggerRepository,
    private val runtime: TriggerRuntimeContract,
    private val prefs: UserPreferencesRepository,
) : ViewModel() {

    val uiState: StateFlow<TriggerListUiState> = combine(
        repository.observeAll(),
        runtime.status,
        runtime.access,
        prefs.preferences.map { it.showTriggerIndicator },
    ) { triggers, status, access, indicator ->
        TriggerListUiState(triggers, status, access, indicator, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TriggerListUiState())

    private val _events = MutableSharedFlow<TriggerListEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<TriggerListEvent> = _events.asSharedFlow()

    fun refreshAccess() = runtime.refreshAccess()

    fun setEnabled(id: TriggerId, enabled: Boolean) = viewModelScope.launch {
        report(repository.setEnabled(id, enabled))
        if (!enabled && uiState.value.runtime.manuallyArmedId == id.value) runtime.disarm()
    }

    fun setArmed(id: TriggerId, armed: Boolean) = viewModelScope.launch {
        if (armed) report(runtime.arm(id.value)) else runtime.disarm()
    }

    fun setIndicatorVisible(visible: Boolean) = viewModelScope.launch { prefs.setShowTriggerIndicator(visible) }

    fun duplicate(id: TriggerId) = viewModelScope.launch {
        when (val r = repository.duplicate(id)) {
            is AppResult.Ok -> _events.tryEmit(TriggerListEvent.Duplicated(r.value.id))
            is AppResult.Err -> _events.tryEmit(TriggerListEvent.Error(r.error))
        }
    }

    fun delete(id: TriggerId) = viewModelScope.launch {
        if (uiState.value.runtime.manuallyArmedId == id.value) runtime.disarm()
        report(repository.delete(id))
    }

    fun exportAll() = viewModelScope.launch {
        val configs: List<TriggerConfiguration> = repository.all().map { it.config }
        _events.tryEmit(TriggerListEvent.Exported(TriggerJson.encodeDocument(configs), configs.size))
    }

    fun import(json: String) = viewModelScope.launch {
        when (val doc = TriggerJson.decodeDocument(json)) {
            is AppResult.Err -> _events.tryEmit(TriggerListEvent.Error(doc.error))
            is AppResult.Ok -> when (val stored = repository.importAll(doc.value.triggers)) {
                is AppResult.Ok -> _events.tryEmit(TriggerListEvent.Imported(stored.value))
                is AppResult.Err -> _events.tryEmit(TriggerListEvent.Error(stored.error))
            }
        }
    }

    private fun report(result: AppResult<*>) {
        if (result is AppResult.Err) _events.tryEmit(TriggerListEvent.Error(result.error))
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
