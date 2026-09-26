package com.macroandroid.feature.scheduling.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.model.ScheduleId
import com.macroandroid.core.common.contract.SchedulerContract
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.database.repository.MacroRepository
import com.macroandroid.core.database.repository.ScheduleRepository
import com.macroandroid.core.database.repository.StoredSchedule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Instant

data class ScheduleRow(val stored: StoredSchedule, val macroName: String, val macroEnabled: Boolean)

data class SchedulesUiState(
    val rows: ImmutableList<ScheduleRow> = persistentListOf(),
    val macroFilter: MacroId? = null,
    val macroName: String? = null,
    val loaded: Boolean = false,
)

sealed interface SchedulesEvent {
    data class Error(val error: AppError) : SchedulesEvent
    data object Deleted : SchedulesEvent
}

/** FR-SCH-1/4: all schedules, or those of one macro when opened from the macro list. */
@HiltViewModel
class SchedulesViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val schedules: ScheduleRepository,
    macros: MacroRepository,
    private val scheduler: SchedulerContract,
) : ViewModel() {
    val macroFilter: MacroId? = savedState.get<String>(ARG_MACRO_ID)?.let(::MacroId)

    private val _events = MutableSharedFlow<SchedulesEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SchedulesEvent> = _events.asSharedFlow()

    private val source = macroFilter?.let(schedules::observeForMacro) ?: schedules.observeAll()

    val uiState: StateFlow<SchedulesUiState> = combine(source, macros.observeSummaries()) { list, summaries ->
        val byId = summaries.associateBy { it.id }
        SchedulesUiState(
            rows = list.map { s ->
                val m = byId[s.spec.macroId]
                ScheduleRow(s, m?.name ?: "?", m?.enabled == true)
            }.sortedWith(compareBy<ScheduleRow>({ !it.stored.spec.enabled }, { it.stored.nextRunAt ?: Instant.DISTANT_FUTURE })).toImmutableList(),
            macroFilter = macroFilter,
            macroName = macroFilter?.let { byId[it]?.name },
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SchedulesUiState())

    fun setEnabled(id: ScheduleId, enabled: Boolean) = viewModelScope.launch {
        when (val r = schedules.setEnabled(id, enabled)) {
            is AppResult.Ok -> scheduler.plan(id.value)
            is AppResult.Err -> _events.emit(SchedulesEvent.Error(r.error))
        }
    }

    fun delete(id: ScheduleId) = viewModelScope.launch {
        scheduler.cancel(id.value)
        when (val r = schedules.delete(id)) {
            is AppResult.Ok -> _events.emit(SchedulesEvent.Deleted)
            is AppResult.Err -> _events.emit(SchedulesEvent.Error(r.error))
        }
    }

    companion object {
        const val ARG_MACRO_ID = "macroId"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
