package com.macroandroid.feature.execution.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroandroid.automation.android.service.MacroRunner
import com.macroandroid.automation.engine.ExecutionRecord
import com.macroandroid.automation.engine.ExecutionState
import com.macroandroid.automation.model.ExecutionId
import com.macroandroid.automation.model.MacroId
import com.macroandroid.core.database.repository.ExecutionRepository
import com.macroandroid.core.database.repository.MacroRepository
import com.macroandroid.core.database.repository.MacroSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryFilter(val macroId: MacroId? = null, val state: ExecutionState? = null)

data class ExecutionsUiState(
    val active: ImmutableList<ExecutionRecord> = persistentListOf(),
    val history: ImmutableList<ExecutionRecord> = persistentListOf(),
    val filter: HistoryFilter = HistoryFilter(),
    val macros: ImmutableList<MacroSummary> = persistentListOf(),
    val pageSize: Int = PAGE_SIZE,
    val canLoadMore: Boolean = false,
    val loaded: Boolean = false,
) {
    companion object {
        const val PAGE_SIZE = 50
    }
}

/** FR-EXE-2/5: live monitor of active runs plus paged, filterable history. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ExecutionsViewModel @Inject constructor(
    private val executions: ExecutionRepository,
    macros: MacroRepository,
    private val runner: MacroRunner,
) : ViewModel() {

    private val filter = MutableStateFlow(HistoryFilter())
    private val limit = MutableStateFlow(ExecutionsUiState.PAGE_SIZE)

    private val history = combine(filter, limit) { f, l -> f to l }
        .flatMapLatest { (f, l) -> executions.observeHistory(f.macroId, f.state, limit = l + 1, offset = 0) }

    val uiState: StateFlow<ExecutionsUiState> = combine(
        executions.observeActive(),
        history,
        filter,
        limit,
        macros.observeSummaries(),
    ) { active, hist, f, l, summaries ->
        ExecutionsUiState(
            active = active.toImmutableList(),
            history = hist.take(l).toImmutableList(),
            filter = f,
            macros = summaries.toImmutableList(),
            canLoadMore = hist.size > l,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ExecutionsUiState())

    fun setMacroFilter(id: MacroId?) {
        filter.update { it.copy(macroId = id) }
        limit.value = ExecutionsUiState.PAGE_SIZE
    }

    fun setStateFilter(state: ExecutionState?) {
        filter.update { it.copy(state = state) }
        limit.value = ExecutionsUiState.PAGE_SIZE
    }

    fun loadMore() = limit.update { it + ExecutionsUiState.PAGE_SIZE }

    fun cancel(id: ExecutionId) = runner.cancel(id)
    fun pause(id: ExecutionId) = runner.pause(id)
    fun resume(id: ExecutionId) = runner.resume(id)
    fun cancelAll() = runner.cancelAll()

    fun clearHistory() = viewModelScope.launch { executions.deleteAll() }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
